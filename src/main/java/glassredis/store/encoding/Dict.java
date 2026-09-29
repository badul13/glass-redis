package glassredis.store.encoding;

import glassredis.store.Key;

import java.util.function.BiConsumer;

/**
 * dict — Redis 의 해시 테이블. Redis 7.2 {@code dict.c} 를 옮겼다. 큰 Hash, Set, Sorted Set 이 이걸 쓴다.
 *
 * <p>버킷 배열 + 체이닝이라는 뼈대는 자바 {@code HashMap} 과 같다. 다른 건 <b>테이블을 키우는 방법</b>이다.
 *
 * <h2>점진적 rehash</h2>
 * {@code HashMap} 은 꽉 차면 두 배 크기 배열을 만들고 <b>그 자리에서 전부</b> 옮긴다. 원소가 천만 개면
 * 그 한 번의 {@code put} 이 수백 ms 를 먹는다. 명령을 스레드 하나로 줄 세워 처리하는 Redis 에서 이건 치명적이다 —
 * 그 동안 뒤에 선 모든 클라이언트가 멈춘다.
 *
 * <p>그래서 dict 는 테이블을 두 개 들고 있다.
 * <pre>
 *   ht[0]  옛 테이블  [■][ ][■][■][ ][■][ ][■]     ← rehashidx 부터 아직 안 옮긴 버킷
 *   ht[1]  새 테이블  [ ][■][ ][ ][■][ ][ ][ ][ ][■][ ][ ][ ][ ][■][ ]
 * </pre>
 * 키울 때는 새 테이블만 만들어 두고, 그 뒤로 찾기·넣기·지우기가 올 때마다 <b>버킷 하나씩</b> 옮긴다
 * ({@link #rehashStep}). 옮기는 동안 찾기는 두 테이블을 다 보고, 새로 넣는 건 새 테이블에만 넣는다.
 * 옛 테이블이 비면 새 테이블이 ht[0] 자리를 차지하고 끝난다. 비용이 명령 하나하나에 잘게 나뉘어 붙는 셈이다.
 *
 * <h2>크기 규칙</h2>
 * <ul>
 *   <li>처음 크기는 4. 크기는 늘 2의 거듭제곱이라 버킷 번호는 {@code hash & (size-1)} 이다.</li>
 *   <li>원소 수가 버킷 수에 닿으면(1:1) 원소 수+1 이상인 가장 작은 2의 거듭제곱으로 키운다.</li>
 *   <li>줄이기는 dict 가 알아서 하지 않는다. 지우는 쪽(Hash, Set, Sorted Set 명령)이 채움률이 10% 아래로
 *       떨어졌는지 보고 {@link #resize} 를 부른다. 줄일 때도 똑같이 점진적으로 옮긴다.</li>
 * </ul>
 *
 * <p>키는 {@link Key}(내용으로 비교되는 바이트 배열)이고, 해시는 {@link SipHash} 다.
 */
public final class Dict<V> {

    static final int INITIAL_EXP = 2;
    static final int INITIAL_SIZE = 1 << INITIAL_EXP;
    /** 채움률이 이 퍼센트 아래면 줄일 때가 됐다(server.h 의 HASHTABLE_MIN_FILL). */
    private static final int MIN_FILL_PERCENT = 10;

    private static final class Entry<V> {
        final Key key;
        V value;
        Entry<V> next;

        Entry(Key key, V value, Entry<V> next) {
            this.key = key;
            this.value = value;
            this.next = next;
        }
    }

    @SuppressWarnings("unchecked")
    private final Entry<V>[][] table = new Entry[2][];
    private final int[] sizeExp = {-1, -1};
    private final long[] used = new long[2];
    /** 옮기는 중인 옛 테이블의 버킷 번호. -1 이면 옮기는 중이 아니다. */
    private long rehashIdx = -1;

    public long size() {
        return used[0] + used[1];
    }

    public boolean isRehashing() {
        return rehashIdx != -1;
    }

    /** 버킷 수. 옮기는 중이면 두 테이블을 합친 수다(dictSlots). */
    public long slots() {
        return tableSize(0) + tableSize(1);
    }

    /** 대시보드용: 두 테이블의 크기와 원소 수, 옮기는 위치. */
    public long tableSize(int t) {
        return sizeExp[t] < 0 ? 0 : 1L << sizeExp[t];
    }

    public long tableUsed(int t) {
        return used[t];
    }

    public long rehashIndex() {
        return rehashIdx;
    }

    // --- 찾기, 넣기, 지우기 ---

    public V get(Key key) {
        Entry<V> e = find(key);
        return e == null ? null : e.value;
    }

    public boolean containsKey(Key key) {
        return find(key) != null;
    }

    /** 새로 넣었으면 {@code true}. 이미 있으면 아무것도 바꾸지 않고 {@code false}(dictAdd). */
    public boolean add(Key key, V value) {
        return addRaw(key, value) != null;
    }

    /** 넣거나 값을 바꾼다. 새로 넣었으면 {@code true}(dictReplace). */
    public boolean put(Key key, V value) {
        Entry<V> existing = find(key);
        if (existing != null) {
            existing.value = value;
            return false;
        }
        addRaw(key, value);
        return true;
    }

    /** 지웠으면 {@code true}. */
    public boolean remove(Key key) {
        if (size() == 0) {
            return false;
        }
        if (isRehashing()) {
            rehashStep();
        }
        long h = SipHash.hash(key.bytes());
        for (int t = 0; t <= 1; t++) {
            if (table[t] == null) {
                continue;
            }
            int idx = (int) (h & mask(t));
            Entry<V> prev = null;
            for (Entry<V> e = table[t][idx]; e != null; prev = e, e = e.next) {
                if (e.key.equals(key)) {
                    if (prev == null) {
                        table[t][idx] = e.next;
                    } else {
                        prev.next = e.next;
                    }
                    used[t]--;
                    return true;
                }
            }
            if (!isRehashing()) {
                break;
            }
        }
        return false;
    }

    /**
     * 넣고 빼는 쪽이 지운 뒤에 부른다. 채움률이 10% 아래면 원소 수에 맞춰 줄인다.
     * 실제 Redis 의 {@code if (htNeedsResize(d)) dictResize(d)} 두 줄을 합친 것이다.
     */
    public void shrinkIfNeeded() {
        long slots = slots();
        if (slots > INITIAL_SIZE && size() * 100 / slots < MIN_FILL_PERCENT) {
            resize();
        }
    }

    /** 원소가 다 들어가는 가장 작은 크기로 맞춘다(dictResize). 옮기는 중이면 하지 않는다. */
    public void resize() {
        if (isRehashing()) {
            return;
        }
        expand(Math.max(used[0], INITIAL_SIZE));
    }

    /** 최소 size 개를 담을 수 있게 키운다(dictExpand). 미리 크기를 알 때 한 번에 잡아둔다. */
    public void expand(long size) {
        if (isRehashing() || used[0] > size) {
            return;
        }
        int exp = nextExp(size);
        if (exp == sizeExp[0]) {
            return;
        }
        @SuppressWarnings("unchecked")
        Entry<V>[] fresh = new Entry[1 << exp];
        if (table[0] == null) {
            // 처음 만드는 거라면 옮길 게 없다. 바로 ht[0] 으로 쓴다.
            table[0] = fresh;
            sizeExp[0] = exp;
            used[0] = 0;
            return;
        }
        table[1] = fresh;
        sizeExp[1] = exp;
        used[1] = 0;
        rehashIdx = 0;
    }

    /** 모든 원소를 훑는다. ht[0] 의 버킷 순서대로, 그다음 ht[1]. 훑는 동안에는 옮기지 않는다. */
    public void forEach(BiConsumer<Key, V> visitor) {
        for (int t = 0; t <= 1; t++) {
            if (table[t] == null) {
                continue;
            }
            for (Entry<V> bucket : table[t]) {
                for (Entry<V> e = bucket; e != null; e = e.next) {
                    visitor.accept(e.key, e.value);
                }
            }
        }
    }

    // --- 속 ---

    private Entry<V> find(Key key) {
        if (size() == 0) {
            return null;
        }
        if (isRehashing()) {
            rehashStep();
        }
        long h = SipHash.hash(key.bytes());
        for (int t = 0; t <= 1; t++) {
            if (table[t] == null) {
                continue;
            }
            for (Entry<V> e = table[t][(int) (h & mask(t))]; e != null; e = e.next) {
                if (e.key.equals(key)) {
                    return e;
                }
            }
            if (!isRehashing()) {
                return null;
            }
        }
        return null;
    }

    /** 없으면 넣고 그 칸을 준다. 이미 있으면 {@code null}(dictAddRaw). */
    private Entry<V> addRaw(Key key, V value) {
        long h = SipHash.hash(key.bytes());
        if (isRehashing()) {
            rehashStep();
        }
        expandIfNeeded();
        for (int t = 0; t <= 1; t++) {
            if (table[t] == null) {
                continue;
            }
            for (Entry<V> e = table[t][(int) (h & mask(t))]; e != null; e = e.next) {
                if (e.key.equals(key)) {
                    return null;
                }
            }
            if (!isRehashing()) {
                break;
            }
        }
        // 옮기는 중이면 새 테이블에 넣는다. 옛 테이블은 비워 가는 중이니까.
        int t = isRehashing() ? 1 : 0;
        int idx = (int) (h & mask(t));
        // 버킷의 맨 앞에 넣는다. 최근에 넣은 게 곧 다시 찾아질 가능성이 높다는 게 Redis 의 설명이다.
        Entry<V> entry = new Entry<>(key, value, table[t][idx]);
        table[t][idx] = entry;
        used[t]++;
        return entry;
    }

    private void expandIfNeeded() {
        if (isRehashing()) {
            return;
        }
        if (table[0] == null) {
            expand(INITIAL_SIZE);
            return;
        }
        if (used[0] >= tableSize(0)) {
            expand(used[0] + 1);
        }
    }

    /** 찾기·넣기·지우기마다 한 번씩 부르는, 버킷 하나 옮기기(_dictRehashStep). */
    private void rehashStep() {
        rehash(1);
    }

    /**
     * 버킷 n 개를 옮긴다(dictRehash). 빈 버킷만 계속 나오면 n×10 개까지만 보고 멈춘다 —
     * 한 번에 하는 일의 양에 상한을 두려는 것이다.
     */
    boolean rehash(int n) {
        if (!isRehashing()) {
            return false;
        }
        int emptyVisits = n * 10;
        while (n-- > 0 && used[0] != 0) {
            while (table[0][(int) rehashIdx] == null) {
                rehashIdx++;
                if (--emptyVisits == 0) {
                    return true;
                }
            }
            Entry<V> e = table[0][(int) rehashIdx];
            while (e != null) {
                Entry<V> next = e.next;
                int h;
                if (sizeExp[1] > sizeExp[0]) {
                    h = (int) (SipHash.hash(e.key.bytes()) & mask(1));
                } else {
                    // 줄이는 중: 크기가 2의 거듭제곱이라 큰 테이블의 버킷 번호를 잘라내면 작은 테이블의 번호다.
                    h = (int) (rehashIdx & mask(1));
                }
                e.next = table[1][h];
                table[1][h] = e;
                used[0]--;
                used[1]++;
                e = next;
            }
            table[0][(int) rehashIdx] = null;
            rehashIdx++;
        }
        if (used[0] == 0) {
            table[0] = table[1];
            used[0] = used[1];
            sizeExp[0] = sizeExp[1];
            table[1] = null;
            used[1] = 0;
            sizeExp[1] = -1;
            rehashIdx = -1;
            return false;
        }
        return true;
    }

    private long mask(int t) {
        return tableSize(t) - 1;
    }

    /** size 이상인 가장 작은 2의 거듭제곱의 지수. 최소 4(2^2). */
    static int nextExp(long size) {
        if (size <= INITIAL_SIZE) {
            return INITIAL_EXP;
        }
        if (size >= Long.MAX_VALUE) {
            return 63;
        }
        return 64 - Long.numberOfLeadingZeros(size - 1);
    }
}
