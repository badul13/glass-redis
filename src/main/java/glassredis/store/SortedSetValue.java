package glassredis.store;

import glassredis.store.encoding.Dict;
import glassredis.store.encoding.Doubles;
import glassredis.store.encoding.Listpack;

import java.util.Arrays;

/**
 * Sorted Set. 작을 때는 listpack, 커지면 skiplist(해시 + 스킵 리스트)에 담는다. Redis 7.2 {@code t_zset.c} 의 규칙을 따른다.
 *
 * <h2>listpack 인코딩</h2>
 * {@code [멤버, 점수, 멤버, 점수, ...]} 를 <b>점수 순으로 정렬한 채</b> 이어 붙인다. 점수는 2^62 이하의 정수면
 * 정수 인코딩으로, 아니면 {@code "1.5"} 같은 문자열로 들어간다. 무엇을 하든 처음부터 훑는다 — 순위를 세는 것도,
 * 점수를 찾는 것도 O(n) 이다. 멤버가 128개 이하일 때는 그래도 스킵 리스트보다 빠르고 메모리는 훨씬 적게 쓴다.
 *
 * <h2>skiplist 인코딩</h2>
 * 해시(dict: 멤버 → 점수)와 {@link SkipList} 에 같은 데이터를 나눠 담는다. 점수 찾기는 해시로 O(1),
 * 순위·구간은 스킵 리스트로 O(log n).
 *
 * <h2>전환 규칙</h2>
 * <ul>
 *   <li>새로 만들 때: 넣을 개수가 128({@code zset-max-listpack-entries}) 이하이고 <b>첫 멤버</b>가 64바이트
 *       ({@code zset-max-listpack-value}) 이하면 listpack, 아니면 곧장 skiplist.</li>
 *   <li>새 멤버를 넣을 때: 넣으면 129개가 되거나 그 멤버가 64바이트를 넘으면 skiplist 로 바꾼 뒤 넣는다.</li>
 *   <li>한 번 skiplist 가 되면 줄어도 돌아가지 않는다.</li>
 * </ul>
 */
public final class SortedSetValue implements Value {

    static final int MAX_LISTPACK_ENTRIES = 128;
    static final int MAX_LISTPACK_VALUE = 64;

    /** 멤버 하나를 받는 콜백. 점수는 이미 double 로 풀어서 준다. */
    @FunctionalInterface
    public interface MemberVisitor {
        void visit(byte[] member, double score);
    }

    private Listpack listpack;
    private Dict<Double> dict;
    private SkipList order;

    private SortedSetValue() {
    }

    /** 넣을 개수와 첫 멤버의 길이를 보고 인코딩을 고른다(zsetTypeCreate). */
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

    /** 기존 Sorted Set 에 많이 넣기 전에 부른다(zsetTypeMaybeConvert). */
    public void prepareForAdd(long sizeHint) {
        if (listpack != null && sizeHint > MAX_LISTPACK_ENTRIES) {
            convertToSkipList(sizeHint);
        }
    }

    /** 없으면 {@code null}. */
    public Double score(Key member) {
        if (listpack != null) {
            int p = findMember(member.bytes());
            return p == -1 ? null : scoreAt(listpack.next(p));
        }
        return dict.get(member);
    }

    /**
     * 넣거나 점수를 바꾼다. 새 멤버였으면 {@code true}.
     * 점수가 바뀌면 지웠다가 제자리를 다시 찾아 넣는다 — 두 인코딩 모두 정렬을 유지해야 해서다.
     */
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

    /** 뺐으면 {@code true}. */
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

    /** 0부터 세는 순위(낮은 점수가 0). 없으면 -1. */
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

    /** 점수 구간에 드는 첫 멤버의 순위(0부터). 없으면 -1. */
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

    /** 점수 구간에 드는 마지막 멤버의 순위(0부터). 없으면 -1. */
    public long lastRankIn(ScoreRange range) {
        if (listpack != null) {
            long rank = size() - 1;
            for (int p = listpack.last(); p != -1; p = listpack.prev(listpack.prev(p))) {
                // 뒤에서 걸으면 점수 원소를 먼저 만난다. 멤버는 그 바로 앞이다.
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

    /**
     * 순위 start..end(포함, 0부터) 의 멤버를 차례로 넘긴다. reverse 면 높은 점수부터 센 순위다.
     * listpack 은 그 자리까지 걷고, skiplist 는 span 으로 첫 노드를 바로 찾은 뒤 걷는다.
     */
    public void forEachInRankRange(long start, long end, boolean reverse, MemberVisitor visitor) {
        long count = end - start + 1;
        if (listpack != null) {
            // 원소는 멤버와 점수가 번갈아 있으니 순위 r 의 멤버는 2r 번째다.
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

    // --- listpack 속 ---

    private int findMember(byte[] member) {
        for (int p = listpack.first(); p != -1; p = listpack.next(listpack.next(p))) {
            if (listpack.equalsAt(p, member)) {
                return p;
            }
        }
        return -1;
    }

    /** 앞 멤버의 위치. 처음이면 -1. */
    private int prevMember(int memberPos) {
        int scorePos = listpack.prev(memberPos);
        return scorePos == -1 ? -1 : listpack.prev(scorePos);
    }

    private double scoreAt(int p) {
        return listpack.isInteger(p) ? listpack.integer(p) : Doubles.parse(listpack.get(p));
    }

    /**
     * 정렬을 지키며 넣는다(zzlInsert). 처음부터 걸으며 "점수가 더 크거나, 같은 점수에 멤버가 사전순으로 뒤"인
     * 첫 자리 앞에 넣는다.
     */
    private void insertSorted(byte[] member, double score) {
        byte[] scoreBytes = Doubles.isSmallInteger(score)
                ? Long.toString((long) score).getBytes(java.nio.charset.StandardCharsets.US_ASCII)
                : Doubles.format(score);
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
