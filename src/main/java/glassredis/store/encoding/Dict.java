package glassredis.store.encoding;

import glassredis.store.Key;

import java.util.function.BiConsumer;

/**
 * 체이닝 해시 테이블 - Redis 7.2 dict.c
 * 테이블 두 개로 점진적 rehash - 조회·삽입·삭제마다 버킷 하나씩 이동 (_dictRehashStep)
 * 원소 수가 버킷 수에 닿으면 확장, 축소는 호출 측이 shrinkIfNeeded·resize 로
 * 연산마다 rehash 기회 횟수도 Redis 와 같게 - 찾고 넣기는 addRaw 한 번 (dictReplace·dictAddRaw)
 */
public final class Dict<V> {

    static final int INITIAL_EXP = 2;
    public static final int INITIAL_SIZE = 1 << INITIAL_EXP;
    /** HASHTABLE_MIN_FILL */
    private static final int MIN_FILL_PERCENT = 10;
    /** dict_force_resize_ratio - AVOID 중에도 이 배율 넘으면 확장·rehash */
    static final int FORCE_RESIZE_RATIO = 5;
    /**
     * 자바 배열 길이 한도 - 2^30 버킷
     * Redis 는 2^63 까지 계산 후 할당 실패로 거절, 여기선 할당 전에 거절
     */
    static final int MAX_EXP = 30;

    /**
     * dictResizeEnable - 자식 작업 중 확장·rehash 억제 (updateDictResizePolicy)
     * FORBID 는 fork 된 자식 전용이라 없음 - 이 프로젝트의 자식 역할은 파일 쓰기 스레드뿐
     */
    public enum ResizePolicy { ENABLE, AVOID }

    /**
     * dict_can_resize - Redis 는 프로세스 전역 static
     * 여기선 실행 스레드별 - 한 JVM 에 서버 여럿(복제 테스트)이 서로의 정책을 덮지 않도록
     */
    private static final ThreadLocal<ResizePolicy> RESIZE_POLICY = ThreadLocal.withInitial(() -> ResizePolicy.ENABLE);

    /** dictSetResizeEnabled - 지금 스레드에서 다루는 모든 dict 에 적용 */
    public static void setResizePolicy(ResizePolicy policy) {
        RESIZE_POLICY.set(policy);
    }

    public static ResizePolicy resizePolicy() {
        return RESIZE_POLICY.get();
    }

    /** 확장 메모리 확보 실패 - dictTryExpand 의 DICT_ERR, RDB 로더는 손상으로 처리 */
    public static final class ExpandFailedException extends RuntimeException {

        private final long size;

        public ExpandFailedException(long size) {
            super("OOM in dictTryExpand " + size);
            this.size = size;
        }

        public long size() {
            return size;
        }
    }

    /** dictEntry - find 로 받은 항목은 다시 찾지 않고 값 교체 (dictSetVal) */
    public static final class Entry<V> {
        final Key key;
        V value;
        Entry<V> next;

        Entry(Key key, V value, Entry<V> next) {
            this.key = key;
            this.value = value;
            this.next = next;
        }

        public Key key() {
            return key;
        }

        public V value() {
            return value;
        }

        /** dictSetVal */
        public void setValue(V value) {
            this.value = value;
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

    /** _dictRehashStep 호출 수 - 연산별 rehash 기회 횟수 확인용 (테스트) */
    private long rehashSteps;

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

    /** 테이블별 원소 수 - rehash 진행 확인용 (테스트) */
    long tableUsed(int t) {
        return used[t];
    }

    long rehashSteps() {
        return rehashSteps;
    }

    /** dictFind + dictGetVal */
    public V get(Key key) {
        Entry<V> e = find(key);
        return e == null ? null : e.value;
    }

    public boolean containsKey(Key key) {
        return find(key) != null;
    }

    /** 이미 있으면 변경 없이 false - dictAdd */
    public boolean add(Key key, V value) {
        return addRaw(key, value, null) != null;
    }

    /** 새로 넣었으면 true, 있으면 값만 교체 - dictReplace (dictAddRaw 한 번) */
    public boolean put(Key key, V value) {
        @SuppressWarnings("unchecked")
        Entry<V>[] existing = new Entry[1];
        if (addRaw(key, value, existing) != null) {
            return true;
        }
        existing[0].value = value;
        return false;
    }

    /** 없으면 넣고 null, 있으면 그대로 두고 기존 값 - dictAddRaw(d, key, &existing) */
    public V putIfAbsent(Key key, V value) {
        @SuppressWarnings("unchecked")
        Entry<V>[] existing = new Entry[1];
        return addRaw(key, value, existing) != null ? null : existing[0].value;
    }

    /** dictDelete */
    public boolean remove(Key key) {
        return unlinkEntry(key) != null;
    }

    /** 지운 값, 없으면 null - dictUnlink (값을 쓴 뒤 해제하는 경로) */
    public V unlink(Key key) {
        Entry<V> e = unlinkEntry(key);
        return e == null ? null : e.value;
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

    /**
     * 최대 ms 동안 100버킷씩 이동, 반환값은 이동 수 - dictRehashMilliseconds
     * 시계는 ms 단위 (timeInMilliseconds) - ms 를 넘긴 뒤 처음 보는 순간 중단
     */
    public int rehashMilliseconds(int ms) {
        if (pauseRehash > 0) {
            return 0;
        }
        long start = System.currentTimeMillis();
        int rehashes = 0;
        while (rehash(100)) {
            rehashes += 100;
            if (System.currentTimeMillis() - start > ms) {
                break;
            }
        }
        return rehashes;
    }

    /**
     * 버킷 하나 탐색 후 다음 커서 반환 - dictScan, 0이면 한 바퀴 완료
     * 비트 반전 순서로 커서 증가 → 도중 테이블 크기 변경에도 처음부터 있던 키는 최소 1회 방문
     * visitor에서 현재 키 삭제 가능, 그동안 rehash 정지
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

    /**
     * 원소 수에 맞는 최소 크기로 축소 - dictResize
     * 자식 작업 중(AVOID)이거나 rehash 중이면 거절
     */
    public boolean resize() {
        if (RESIZE_POLICY.get() != ResizePolicy.ENABLE || isRehashing()) {
            return false;
        }
        return expand(Math.max(used[0], INITIAL_SIZE));
    }

    /** 실제로 새 테이블을 만들었으면 true - dictExpand */
    public boolean expand(long size) {
        return expandInternal(size, false);
    }

    /**
     * 메모리 확보 실패만 false - dictTryExpand
     * 같은 크기·rehash 중 등으로 건너뛴 경우는 true (Redis 와 동일)
     */
    public boolean tryExpand(long size) {
        try {
            expandInternal(size, true);
            return true;
        } catch (ExpandFailedException e) {
            return false;
        }
    }

    /** _dictExpand - failLoudly 면 할당 실패를 예외로 */
    private boolean expandInternal(long size, boolean failLoudly) {
        if (isRehashing() || used[0] > size) {
            return false;
        }
        int exp = nextExp(size);
        // 넘침 검사 - Redis 는 2^63, 여기선 자바 배열 한도
        if (exp > MAX_EXP) {
            if (failLoudly) {
                throw new ExpandFailedException(size);
            }
            return false;
        }
        if (exp == sizeExp[0]) {
            return false;
        }
        Entry<V>[] fresh;
        try {
            @SuppressWarnings("unchecked")
            Entry<V>[] allocated = new Entry[1 << exp];
            fresh = allocated;
        } catch (OutOfMemoryError oom) {
            // ztrycalloc 실패 - dictTryExpand 만 살아남음
            if (failLoudly) {
                throw new ExpandFailedException(size);
            }
            throw oom;
        }
        if (table[0] == null) {
            table[0] = fresh;
            sizeExp[0] = exp;
            used[0] = 0;
            return true;
        }
        table[1] = fresh;
        sizeExp[1] = exp;
        used[1] = 0;
        rehashIdx = 0;
        return true;
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

    /** 없으면 null - dictFind */
    public Entry<V> find(Key key) {
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

    /** dictGenericDelete - 빈 dict 면 rehash 없이 끝 */
    private Entry<V> unlinkEntry(Key key) {
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
                    return e;
                }
            }
            if (!isRehashing()) {
                break;
            }
        }
        return null;
    }

    /**
     * 있으면 null 반환 후 existing[0] 에 기존 항목 - dictAddRaw (dictFindPositionForInsert)
     * existing 은 null 가능
     */
    private Entry<V> addRaw(Key key, V value, Entry<V>[] existing) {
        long h = SipHash.hash(key.bytes());
        if (isRehashing()) {
            rehashStep();
        }
        expandIfNeeded();
        int idx = 0;
        for (int t = 0; t <= 1; t++) {
            if (table[t] == null) {
                continue;
            }
            idx = (int) (h & mask(t));
            for (Entry<V> e = table[t][idx]; e != null; e = e.next) {
                if (e.key.equals(key)) {
                    if (existing != null) {
                        existing[0] = e;
                    }
                    return null;
                }
            }
            if (!isRehashing()) {
                break;
            }
        }
        // rehash 중이면 새 테이블에만 삽입
        int t = isRehashing() ? 1 : 0;
        // 최근 키가 다시 조회될 가능성 높아 버킷 앞에 삽입
        Entry<V> entry = new Entry<>(key, value, table[t][idx]);
        table[t][idx] = entry;
        used[t]++;
        return entry;
    }

    /**
     * _dictExpandIfNeeded
     * - ENABLE: 원소 수가 버킷 수에 닿으면 두 배
     * - AVOID: 원소/버킷 비가 5 를 넘을 때만
     * 확장 거절(배열 한도)이어도 삽입은 진행 - Redis 는 넣지 않고 실패, 2^30 버킷은 힙 한도상 도달 불가
     */
    private void expandIfNeeded() {
        if (isRehashing()) {
            return;
        }
        if (table[0] == null) {
            expand(INITIAL_SIZE);
            return;
        }
        long size0 = tableSize(0);
        ResizePolicy policy = RESIZE_POLICY.get();
        if ((policy == ResizePolicy.ENABLE && used[0] >= size0) || used[0] / size0 > FORCE_RESIZE_RATIO) {
            expand(used[0] + 1);
        }
    }

    /** _dictRehashStep */
    private void rehashStep() {
        if (pauseRehash == 0) {
            rehashSteps++;
            rehash(1);
        }
    }

    /**
     * 버킷 n개 이동 - dictRehash, 빈 버킷은 n×10개까지만 확인
     * AVOID 중이면 두 테이블 크기 비가 5 미만일 때 정지
     * 남은 작업 있으면 true
     */
    boolean rehash(int n) {
        if (!isRehashing()) {
            return false;
        }
        long s0 = tableSize(0);
        long s1 = tableSize(1);
        if (RESIZE_POLICY.get() == ResizePolicy.AVOID
                && ((s1 > s0 && s1 / s0 < FORCE_RESIZE_RATIO) || (s1 < s0 && s0 / s1 < FORCE_RESIZE_RATIO))) {
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

    /** size 이상인 가장 작은 2의 거듭제곱의 지수, 최소 2, 최대 63 - _dictNextExp */
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
