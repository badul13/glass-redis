package glassredis.store;

import glassredis.store.encoding.Dict;
import glassredis.store.encoding.Doubles;
import glassredis.store.encoding.Listpack;
import glassredis.store.encoding.LongStrings;

import java.util.Arrays;

/**
 * Sorted Set - t_zset.c
 * 작을 때 listpack에 [멤버, 점수, ...] 점수 순 저장 - 모든 연산 O(n)
 * 커지면 dict(멤버 → 점수)와 SkipList에 같은 데이터 이중 보관
 * skiplist 전환 후 복귀 없음
 */
public final class SortedSetValue implements Value {

    /** zset-max-listpack-entries */
    static final int MAX_LISTPACK_ENTRIES = 128;
    /** zset-max-listpack-value */
    static final int MAX_LISTPACK_VALUE = 64;

    /** zsetAdd 입력 - ZADD_IN_INCR, ZADD_IN_NX, ZADD_IN_XX, ZADD_IN_GT, ZADD_IN_LT */
    public static final int IN_INCR = 1;
    public static final int IN_NX = 1 << 1;
    public static final int IN_XX = 1 << 2;
    public static final int IN_GT = 1 << 3;
    public static final int IN_LT = 1 << 4;

    /** zsetAdd 출력 - ZADD_OUT_NOP, ZADD_OUT_NAN, ZADD_OUT_ADDED, ZADD_OUT_UPDATED */
    public static final int OUT_NOP = 1;
    public static final int OUT_NAN = 1 << 1;
    public static final int OUT_ADDED = 1 << 2;
    public static final int OUT_UPDATED = 1 << 3;

    /**
     * zsetAdd 결과
     *
     * @param flags    OUT_* 조합
     * @param newScore 추가·갱신했거나 같은 점수로 남았을 때의 점수 - NOP·NAN 이면 무의미
     */
    public record AddResult(int flags, double newScore) {
        public boolean has(int flag) {
            return (flags & flag) != 0;
        }
    }

    @FunctionalInterface
    public interface MemberVisitor {
        void visit(byte[] member, double score);
    }

    private Listpack listpack;
    private Dict<Double> dict;
    private SkipList order;

    private SortedSetValue() {
    }

    /** 멤버 길이는 첫 멤버만 확인 - zsetTypeCreate */
    public static SortedSetValue create(long sizeHint, int firstMemberLength) {
        SortedSetValue zset = new SortedSetValue();
        if (sizeHint <= MAX_LISTPACK_ENTRIES && firstMemberLength <= MAX_LISTPACK_VALUE) {
            zset.listpack = new Listpack();
        } else {
            zset.dict = new Dict<>();
            zset.dict.expand(sizeHint);
            zset.order = new SkipList();
        }
        return zset;
    }

    /** RDB listpack 덩어리 - 비었으면 null, 128개 초과면 skiplist (멤버 길이는 안 봄) */
    public static SortedSetValue ofListpack(Listpack listpack) {
        if (listpack.length() == 0) {
            return null;
        }
        SortedSetValue zset = new SortedSetValue();
        zset.listpack = listpack;
        if (zset.size() > MAX_LISTPACK_ENTRIES) {
            zset.convertToSkipList(zset.size());
        }
        return zset;
    }

    /**
     * RDB 멤버 나열 형식 - 일단 skiplist로 받고 finishLoading 에서 판단
     *
     * @throws Dict.ExpandFailedException 미리 확장 실패 (dictTryExpand) - 로더가 손상으로 처리
     */
    public static SortedSetValue forLoading(long length) {
        SortedSetValue zset = new SortedSetValue();
        zset.dict = new Dict<>();
        if (length > Dict.INITIAL_SIZE && !zset.dict.tryExpand(length)) {
            throw new Dict.ExpandFailedException(length);
        }
        zset.order = new SkipList();
        return zset;
    }

    /** RDB 멤버 하나 추가 - 중복이면 false (손상) */
    public boolean addLoaded(byte[] member, double score) {
        if (!dict.add(new Key(member), score)) {
            return false;
        }
        order.insert(score, member);
        return true;
    }

    /** 다 읽은 뒤 한도 안이면 listpack - 점수 순 그대로 이어 붙임 (zsetConvert) */
    public void finishLoading(int maxMemberLength) {
        if (size() > MAX_LISTPACK_ENTRIES || maxMemberLength > MAX_LISTPACK_VALUE) {
            return;
        }
        Listpack converted = new Listpack();
        for (SkipList.Node node = order.first(); node != null; node = node.next()) {
            converted.append(node.member());
            converted.append(scoreBytes(node.score()));
        }
        listpack = converted;
        dict = null;
        order = null;
    }

    @Override
    public String typeName() {
        return "zset";
    }

    @Override
    public String encoding() {
        return listpack != null ? "listpack" : "skiplist";
    }

    @Override
    public int size() {
        return listpack != null ? listpack.length() / 2 : order.length();
    }

    /** 다건 삽입 전 호출 - zsetTypeMaybeConvert */
    public void prepareForAdd(long sizeHint) {
        if (listpack != null && sizeHint > MAX_LISTPACK_ENTRIES) {
            convertToSkipList(sizeHint);
        }
    }

    /** 없으면 null - zsetScore (skiplist 는 dictFind 한 번) */
    public Double score(Key member) {
        if (listpack != null) {
            int p = findMember(member.bytes());
            return p == -1 ? null : scoreAt(listpack.next(p));
        }
        return dict.get(member);
    }

    /**
     * 추가·점수 갱신 - zsetAdd
     * - skiplist: 있으면 dictFind 한 번 뒤 그 항목 값만 교체, 없으면 dictFind + dictAdd
     * - NaN 점수, INCR 결과 NaN 이면 OUT_NAN
     *
     * @param inFlags IN_* 조합
     */
    public AddResult add(Key member, double score, int inFlags) {
        boolean incr = (inFlags & IN_INCR) != 0;
        boolean nx = (inFlags & IN_NX) != 0;
        boolean xx = (inFlags & IN_XX) != 0;
        boolean gt = (inFlags & IN_GT) != 0;
        boolean lt = (inFlags & IN_LT) != 0;

        if (Double.isNaN(score)) {
            return new AddResult(OUT_NAN, Double.NaN);
        }

        if (listpack != null) {
            int p = findMember(member.bytes());
            if (p != -1) {
                if (nx) {
                    return new AddResult(OUT_NOP, Double.NaN);
                }
                double current = scoreAt(listpack.next(p));
                if (incr) {
                    score += current;
                    if (Double.isNaN(score)) {
                        return new AddResult(OUT_NAN, Double.NaN);
                    }
                }
                if ((lt && score >= current) || (gt && score <= current)) {
                    return new AddResult(OUT_NOP, Double.NaN);
                }
                if (score != current) {
                    // zzlDelete + zzlInsert
                    listpack.deleteRangeWithEntry(p, 2);
                    insertSorted(member.bytes(), score);
                    return new AddResult(OUT_UPDATED, score);
                }
                return new AddResult(0, score);
            }
            if (xx) {
                return new AddResult(OUT_NOP, Double.NaN);
            }
            if (size() + 1 > MAX_LISTPACK_ENTRIES || member.bytes().length > MAX_LISTPACK_VALUE) {
                convertToSkipList(size() + 1L);
            } else {
                insertSorted(member.bytes(), score);
                return new AddResult(OUT_ADDED, score);
            }
        }

        Dict.Entry<Double> de = dict.find(member);
        if (de != null) {
            if (nx) {
                return new AddResult(OUT_NOP, Double.NaN);
            }
            double current = de.value();
            if (incr) {
                score += current;
                if (Double.isNaN(score)) {
                    return new AddResult(OUT_NAN, Double.NaN);
                }
            }
            if ((lt && score >= current) || (gt && score <= current)) {
                return new AddResult(OUT_NOP, Double.NaN);
            }
            if (score != current) {
                order.updateScore(current, member.bytes(), score);
                de.setValue(score);
                return new AddResult(OUT_UPDATED, score);
            }
            return new AddResult(0, score);
        }
        if (xx) {
            return new AddResult(OUT_NOP, Double.NaN);
        }
        order.insert(score, member.bytes());
        dict.add(member, score);
        return new AddResult(OUT_ADDED, score);
    }

    /** 플래그 없는 zsetAdd - 새 멤버면 true, 있으면 점수만 바꿈 */
    public boolean put(Key member, double score) {
        return add(member, score, 0).has(OUT_ADDED);
    }

    public boolean remove(Key member) {
        if (listpack != null) {
            int p = findMember(member.bytes());
            if (p == -1) {
                return false;
            }
            listpack.deleteRangeWithEntry(p, 2);
            return true;
        }
        // dictUnlink 한 번으로 점수 꺼내고 삭제 - zsetRemoveFromSkiplist
        Double score = dict.unlink(member);
        if (score == null) {
            return false;
        }
        order.delete(score, member.bytes());
        dict.shrinkIfNeeded();
        return true;
    }

    /**
     * 순위와 점수 - zsetRank (output_score)
     *
     * @param rank 0부터 세는 오름차순 순위
     */
    public record Ranked(long rank, double score) {
    }

    /** 0부터 세는 순위, 없으면 -1 */
    public long rank(Key member) {
        Ranked ranked = rankWithScore(member);
        return ranked == null ? -1 : ranked.rank();
    }

    /** 없으면 null - skiplist 는 dictFind 한 번으로 순위와 점수 */
    public Ranked rankWithScore(Key member) {
        if (listpack != null) {
            long rank = 0;
            for (int p = listpack.first(); p != -1; p = listpack.next(listpack.next(p))) {
                if (listpack.equalsAt(p, member.bytes())) {
                    return new Ranked(rank, scoreAt(listpack.next(p)));
                }
                rank++;
            }
            return null;
        }
        Double score = dict.get(member);
        return score == null ? null : new Ranked(order.rank(score, member.bytes()) - 1, score);
    }

    /** 0부터 세는 순위, 없으면 -1 */
    public long firstRankIn(ScoreRange range) {
        if (listpack != null) {
            long rank = 0;
            for (int p = listpack.first(); p != -1; p = listpack.next(listpack.next(p))) {
                double score = scoreAt(listpack.next(p));
                if (range.aboveMin(score)) {
                    return range.belowMax(score) ? rank : -1;
                }
                rank++;
            }
            return -1;
        }
        SkipList.Node node = order.firstInRange(range);
        return node == null ? -1 : order.rank(node.score(), node.member()) - 1;
    }

    /** 0부터 세는 순위, 없으면 -1 */
    public long lastRankIn(ScoreRange range) {
        if (listpack != null) {
            long rank = size() - 1;
            for (int p = listpack.last(); p != -1; p = listpack.prev(listpack.prev(p))) {
                // 뒤에서 걷는 중이라 p는 점수 원소
                double score = scoreAt(p);
                if (range.belowMax(score)) {
                    return range.aboveMin(score) ? rank : -1;
                }
                rank--;
            }
            return -1;
        }
        SkipList.Node node = order.lastInRange(range);
        return node == null ? -1 : order.rank(node.score(), node.member()) - 1;
    }

    /** 순위 start..end - 양 끝 포함, 0부터, reverse면 높은 점수 기준 순위 */
    public void forEachInRankRange(long start, long end, boolean reverse, MemberVisitor visitor) {
        long count = end - start + 1;
        if (listpack != null) {
            // 순위 r의 멤버 = 2r번째 원소
            int p = reverse ? listpack.seek(-2 * start - 2) : listpack.seek(2 * start);
            for (long i = 0; i < count && p != -1; i++) {
                int sp = listpack.next(p);
                visitor.visit(listpack.get(p), scoreAt(sp));
                p = reverse ? prevMember(p) : listpack.next(sp);
            }
            return;
        }
        SkipList.Node node = order.byRank(reverse ? order.length() - start : start + 1);
        for (long i = 0; i < count && node != null; i++) {
            visitor.visit(node.member(), node.score());
            node = reverse ? node.previous() : node.next();
        }
    }

    public Listpack listpack() {
        return listpack;
    }

    public SkipList order() {
        return order;
    }

    public Dict<Double> dict() {
        return dict;
    }

    // --- listpack ---

    /** 멤버 위치, 없으면 -1 - zzlFind (lpFind 로 점수 칸은 건너뛰며 멤버만 비교) */
    private int findMember(byte[] member) {
        int first = listpack.first();
        return first == -1 ? -1 : listpack.find(first, member, 1);
    }

    /** 처음이면 -1 */
    private int prevMember(int memberPos) {
        int scorePos = listpack.prev(memberPos);
        return scorePos == -1 ? -1 : listpack.prev(scorePos);
    }

    private double scoreAt(int p) {
        return listpack.isInteger(p) ? listpack.integer(p) : Doubles.parse(listpack.get(p));
    }

    /** 정렬 유지 삽입 - zzlInsert */
    private void insertSorted(byte[] member, double score) {
        byte[] scoreBytes = scoreBytes(score);
        for (int p = listpack.first(); p != -1; p = listpack.next(listpack.next(p))) {
            double s = scoreAt(listpack.next(p));
            if (s > score || (s == score && Arrays.compareUnsigned(listpack.get(p), member) > 0)) {
                int memberPos = listpack.insert(member, p, Listpack.Where.BEFORE);
                listpack.insert(scoreBytes, memberPos, Listpack.Where.AFTER);
                return;
            }
        }
        listpack.append(member);
        listpack.append(scoreBytes);
    }

    /**
     * listpack 에 넣을 점수 바이트 - zzlInsertAt
     * - double2ll 성공(절댓값 2^62 이하 정수)이면 정수 원소 - -0 도 여기서 정수 0 이 되어 부호 사라짐
     *   그래서 listpack 인코딩 ZSCORE 는 "0", skiplist 인코딩(dict 의 double 그대로)은 "-0"
     * - 그 밖(소수, 2^62 초과, ±inf)은 d2string 문자열 원소 - 0 은 오지 않아 "-0" 표기도 생기지 않음
     */
    private static byte[] scoreBytes(double score) {
        return Doubles.isSmallInteger(score) ? LongStrings.toBytes((long) score) : Doubles.format(score);
    }

    private void convertToSkipList(long sizeHint) {
        Dict<Double> newDict = new Dict<>();
        newDict.expand(sizeHint);
        SkipList newOrder = new SkipList();
        for (int p = listpack.first(); p != -1; p = listpack.next(listpack.next(p))) {
            byte[] member = listpack.get(p);
            double score = scoreAt(listpack.next(p));
            newDict.add(new Key(member), score);
            newOrder.insert(score, member);
        }
        dict = newDict;
        order = newOrder;
        listpack = null;
    }
}
