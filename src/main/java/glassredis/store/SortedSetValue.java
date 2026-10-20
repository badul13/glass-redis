package glassredis.store;

import glassredis.store.encoding.Dict;
import glassredis.store.encoding.Doubles;
import glassredis.store.encoding.Listpack;

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

    /** RDB 멤버 나열 형식 - 일단 skiplist로 받고 finishLoading 에서 판단 */
    public static SortedSetValue forLoading(long length) {
        SortedSetValue zset = new SortedSetValue();
        zset.dict = new Dict<>();
        if (length > Dict.INITIAL_SIZE) {
            zset.dict.expand(length);
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

    /** 없으면 null */
    public Double score(Key member) {
        if (listpack != null) {
            int p = findMember(member.bytes());
            return p == -1 ? null : scoreAt(listpack.next(p));
        }
        return dict.get(member);
    }

    /** 새 멤버면 true */
    public boolean put(Key member, double score) {
        if (listpack != null) {
            int p = findMember(member.bytes());
            if (p != -1) {
                if (scoreAt(listpack.next(p)) != score) {
                    listpack.deleteRangeWithEntry(p, 2);
                    insertSorted(member.bytes(), score);
                }
                return false;
            }
            if (size() + 1 > MAX_LISTPACK_ENTRIES || member.bytes().length > MAX_LISTPACK_VALUE) {
                convertToSkipList(size() + 1L);
            } else {
                insertSorted(member.bytes(), score);
                return true;
            }
        }
        Double previous = dict.get(member);
        if (previous != null) {
            if (previous != score) {
                order.updateScore(previous, member.bytes(), score);
                dict.put(member, score);
            }
            return false;
        }
        dict.add(member, score);
        order.insert(score, member.bytes());
        return true;
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
        Double score = dict.get(member);
        if (score == null) {
            return false;
        }
        dict.remove(member);
        order.delete(score, member.bytes());
        dict.shrinkIfNeeded();
        return true;
    }

    /** 0부터 세는 순위, 없으면 -1 */
    public long rank(Key member) {
        if (listpack != null) {
            long rank = 0;
            for (int p = listpack.first(); p != -1; p = listpack.next(listpack.next(p))) {
                if (listpack.equalsAt(p, member.bytes())) {
                    return rank;
                }
                rank++;
            }
            return -1;
        }
        Double score = dict.get(member);
        return score == null ? -1 : order.rank(score, member.bytes()) - 1;
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

    private int findMember(byte[] member) {
        for (int p = listpack.first(); p != -1; p = listpack.next(listpack.next(p))) {
            if (listpack.equalsAt(p, member)) {
                return p;
            }
        }
        return -1;
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

    /** 정수면 정수 원소, 아니면 문자열 원소 - zzlInsertAt 의 double2ll 분기 */
    private static byte[] scoreBytes(double score) {
        return Doubles.isSmallInteger(score)
                ? Long.toString((long) score).getBytes(java.nio.charset.StandardCharsets.US_ASCII)
                : Doubles.format(score);
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
