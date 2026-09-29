package glassredis.store.encoding;

import glassredis.store.Key;

import java.util.function.BiConsumer;

/**
 * 체이닝 해시 테이블 - Redis 7.2 dict.c
 * 테이블 두 개로 점진적 rehash - 조회·삽입·삭제마다 버킷 하나씩 이동
 * 원소 수가 버킷 수에 닿으면 확장, 축소는 호출 측이 shrinkIfNeeded로
 */
public final class Dict<V> {

    static final int INITIAL_EXP = 2;
    static final int INITIAL_SIZE = 1 << INITIAL_EXP;
    /** HASHTABLE_MIN_FILL */
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
    /** ht[0]에서 다음 이동할 버킷, -1이면 rehash 아님 */
    private long rehashIdx = -1;

    /** 0보다 크면 rehash 정지 - scan 중 버킷 이동 시 키 누락 가능 */
    private int pauseRehash;

    public long size() {
        return used[0] + used[1];
    }

    public boolean isRehashing() {
        return rehashIdx != -1;
    }

    /** 두 테이블 버킷 수 합 - dictSlots */
    public long slots() {
        return tableSize(0) + tableSize(1);
    }

    public long tableSize(int t) {
        return sizeExp[t] < 0 ? 0 : 1L << sizeExp[t];
    }

    public long tableUsed(int t) {
        return used[t];
    }

    public long rehashIndex() {
        return rehashIdx;
    }

    public V get(Key key) {
        Entry<V> e = find(key);
        return e == null ? null : e.value;
    }

    public boolean containsKey(Key key) {
        return find(key) != null;
    }

    /** 이미 있으면 변경 없이 false - dictAdd */
    public boolean add(Key key, V value) {
        return addRaw(key, value) != null;
    }

    /** 새로 넣었으면 true - dictReplace */
    public boolean put(Key key, V value) {
        Entry<V> existing = find(key);
        if (existing != null) {
            existing.value = value;
            return false;
        }
        addRaw(key, value);
        return true;
    }

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

    /** rehash 없는 조회 - 대시보드가 테이블 모양을 바꾸지 않도록 */
    public V peek(Key key) {
        if (size() == 0) {
            return null;
        }
        long h = SipHash.hash(key.bytes());
        for (int t = 0; t <= 1; t++) {
            if (table[t] == null) {
                continue;
            }
            for (Entry<V> e = table[t][(int) (h & mask(t))]; e != null; e = e.next) {
                if (e.key.equals(key)) {
                    return e.value;
                }
            }
            if (!isRehashing()) {
                return null;
            }
        }
        return null;
    }

    /** 삭제 후 호출 - htNeedsResize + dictResize */
    public void shrinkIfNeeded() {
        if (needsResize()) {
            resize();
        }
    }

    /** htNeedsResize */
    public boolean needsResize() {
        long slots = slots();
        return slots > INITIAL_SIZE && size() * 100 / slots < MIN_FILL_PERCENT;
    }

    /** 최대 ms 동안 100버킷씩 이동, 반환값은 이동 수 - dictRehashMilliseconds */
    public int rehashMilliseconds(int ms) {
        if (pauseRehash > 0) {
            return 0;
        }
        long start = System.nanoTime();
        long limit = ms * 1_000_000L;
        int rehashes = 0;
        while (rehash(100)) {
            rehashes += 100;
            if (System.nanoTime() - start > limit) {
                break;
            }
        }
        return rehashes;
    }

    /**
     * 버킷 하나 탐색 후 다음 커서 반환 - dictScan, 0이면 한 바퀴 완료
     * 비트 반전 순서로 커서 증가 → 도중 테이블 크기 변경에도 처음부터 있던 키는 최소 1회 방문
     * visitor에서 현재 키 삭제 가능
     */
    public long scan(long cursor, BiConsumer<Key, V> visitor) {
        if (size() == 0) {
            return 0;
        }
        pauseRehash++;
        try {
            long v = cursor;
            if (!isRehashing()) {
                long m0 = mask(0);
                visitBucket(table[0][(int) (v & m0)], visitor);
                v |= ~m0;
                v = Long.reverse(v);
                v++;
                return Long.reverse(v);
            }
            int small = tableSize(0) <= tableSize(1) ? 0 : 1;
            int large = 1 - small;
            long m0 = mask(small);
            long m1 = mask(large);
            visitBucket(table[small][(int) (v & m0)], visitor);
            // 작은 테이블 버킷 하나에 대응하는 큰 테이블 버킷 전부 방문
            do {
                visitBucket(table[large][(int) (v & m1)], visitor);
                v |= ~m1;
                v = Long.reverse(v);
                v++;
                v = Long.reverse(v);
            } while ((v & (m0 ^ m1)) != 0);
            return v;
        } finally {
            pauseRehash--;
        }
    }

    private void visitBucket(Entry<V> bucket, BiConsumer<Key, V> visitor) {
        Entry<V> e = bucket;
        while (e != null) {
            Entry<V> next = e.next;
            visitor.accept(e.key, e.value);
            e = next;
        }
    }

    /** 원소 수에 맞는 최소 크기로 축소 - dictResize, rehash 중이면 생략 */
    public void resize() {
        if (isRehashing()) {
            return;
        }
        expand(Math.max(used[0], INITIAL_SIZE));
    }

    /** dictExpand */
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

    /** ht[0], ht[1] 버킷 순서로 순회, rehash 없음 */
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

    /** 이미 있으면 null - dictAddRaw */
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
        // rehash 중이면 새 테이블에만 삽입
        int t = isRehashing() ? 1 : 0;
        int idx = (int) (h & mask(t));
        // 최근 키가 다시 조회될 가능성 높아 버킷 앞에 삽입
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

    /** _dictRehashStep */
    private void rehashStep() {
        if (pauseRehash == 0) {
            rehash(1);
        }
    }

    /**
     * 버킷 n개 이동 - dictRehash, 빈 버킷은 n×10개까지만 확인
     * 남은 작업 있으면 true
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
                    // 축소 중 - 해시 재계산 없이 버킷 인덱스 마스킹
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

    /** size 이상인 가장 작은 2의 거듭제곱의 지수, 최소 2 */
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
