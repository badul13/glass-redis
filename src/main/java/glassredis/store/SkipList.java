package glassredis.store;

import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Sorted Set 스킵 리스트 - Redis t_zset.c zskiplist
 * 화살표마다 건너뛰는 노드 수(span) 보유 → 순위 조회 O(log n)
 * 정렬 - 점수, 동점이면 멤버의 부호 없는 바이트 사전순
 * 멤버 중복 방지는 SortedSetValue 담당
 */
public final class SkipList {

    /** ZSKIPLIST_MAXLEVEL */
    static final int MAX_LEVEL = 32;

    /** ZSKIPLIST_P */
    static final double LEVEL_UP_PROBABILITY = 0.25;

    /** 멤버 불변 - 점수만 updateScore가 제자리 수정 */
    public static final class Node {
        private final byte[] member;
        private double score;
        private final Node[] forward;
        private final long[] span;
        private Node backward;

        private Node(int level, double score, byte[] member) {
            this.member = member;
            this.score = score;
            this.forward = new Node[level];
            this.span = new long[level];
        }

        public byte[] member() {
            return member;
        }

        public double score() {
            return score;
        }

        public Node next() {
            return forward[0];
        }

        public Node previous() {
            return backward;
        }

        public int level() {
            return forward.length;
        }

        /** i층 화살표의 span - nil 가리키면 무의미, -1 */
        public long span(int i) {
            return forward[i] == null ? -1 : span[i];
        }
    }

    private final Node header = new Node(MAX_LEVEL, 0, null);
    private Node tail;
    private int level = 1;
    private int length;

    public int length() {
        return length;
    }

    public Node first() {
        return header.forward[0];
    }

    public Node last() {
        return tail;
    }

    /** 규칙은 Node#span과 동일 */
    public long headerSpan(int i) {
        return header.span(i);
    }

    /**
     * 멤버 부재는 호출 측 보장 - zslInsert
     * 해시와 불일치 방지 위해 SortedSetValue만 호출
     */
    void insert(double score, byte[] member) {
        Node[] update = new Node[MAX_LEVEL];
        long[] rank = new long[MAX_LEVEL];

        // 층마다 새 노드의 앞 노드(update)와 그 순위(rank) 탐색
        Node x = header;
        for (int i = level - 1; i >= 0; i--) {
            rank[i] = i == level - 1 ? 0 : rank[i + 1];
            while (x.forward[i] != null && precedes(x.forward[i], score, member)) {
                rank[i] += x.span[i];
                x = x.forward[i];
            }
            update[i] = x;
        }

        int newLevel = randomLevel();
        if (newLevel > level) {
            // 새 층의 머리 화살표는 전체 건너뜀
            for (int i = level; i < newLevel; i++) {
                rank[i] = 0;
                update[i] = header;
                header.span[i] = length;
            }
            level = newLevel;
        }

        x = new Node(newLevel, score, member);
        for (int i = 0; i < newLevel; i++) {
            x.forward[i] = update[i].forward[i];
            update[i].forward[i] = x;
            // rank[0] - rank[i] = i층 앞 노드와 1층 앞 노드 사이 노드 수
            x.span[i] = update[i].span[i] - (rank[0] - rank[i]);
            update[i].span[i] = (rank[0] - rank[i]) + 1;
        }
        // 새 노드보다 높은 층은 새 노드를 넘어가므로 span +1
        for (int i = newLevel; i < level; i++) {
            update[i].span[i]++;
        }

        x.backward = update[0] == header ? null : update[0];
        if (x.forward[0] != null) {
            x.forward[0].backward = x;
        } else {
            tail = x;
        }
        length++;
    }

    /** 삭제 시 true */
    boolean delete(double score, byte[] member) {
        Node[] update = new Node[MAX_LEVEL];
        Node x = header;
        for (int i = level - 1; i >= 0; i--) {
            while (x.forward[i] != null && precedes(x.forward[i], score, member)) {
                x = x.forward[i];
            }
            update[i] = x;
        }
        x = x.forward[0];
        if (x == null || x.score != score || !Arrays.equals(x.member, member)) {
            return false;
        }
        deleteNode(x, update);
        return true;
    }

    /** update[i]는 i층에서 x 바로 앞 노드 - zslDeleteNode */
    private void deleteNode(Node x, Node[] update) {
        for (int i = 0; i < level; i++) {
            if (update[i].forward[i] == x) {
                update[i].span[i] += x.span[i] - 1;
                update[i].forward[i] = x.forward[i];
            } else {
                update[i].span[i]--;
            }
        }
        if (x.forward[0] != null) {
            x.forward[0].backward = x.backward;
        } else {
            tail = x.backward;
        }
        while (level > 1 && header.forward[level - 1] == null) {
            level--;
        }
        length--;
    }

    /** 자리 유지 시 점수만 수정, 아니면 삭제 후 재삽입 - zslUpdateScore */
    void updateScore(double currentScore, byte[] member, double newScore) {
        Node[] update = new Node[MAX_LEVEL];
        Node x = header;
        for (int i = level - 1; i >= 0; i--) {
            while (x.forward[i] != null && precedes(x.forward[i], currentScore, member)) {
                x = x.forward[i];
            }
            update[i] = x;
        }
        x = x.forward[0];
        if (x == null || !isAt(x, currentScore, member)) {
            throw new IllegalStateException("점수를 바꿀 노드가 없습니다");
        }
        // 이웃과 동점이면 멤버 순서까지 비교 필요 → 엄격 비교로 재삽입
        if ((x.backward == null || x.backward.score < newScore)
                && (x.forward[0] == null || x.forward[0].score > newScore)) {
            x.score = newScore;
            return;
        }
        deleteNode(x, update);
        insert(newScore, member);
    }

    /** 1부터 세는 순위, 없으면 0 */
    public long rank(double score, byte[] member) {
        long rank = 0;
        Node x = header;
        for (int i = level - 1; i >= 0; i--) {
            while (x.forward[i] != null
                    && (precedes(x.forward[i], score, member) || isAt(x.forward[i], score, member))) {
                rank += x.span[i];
                x = x.forward[i];
            }
            if (x != header && isAt(x, score, member)) {
                return rank;
            }
        }
        return 0;
    }

    /** 순위는 1부터, 범위 밖이면 null */
    public Node byRank(long rank) {
        long traversed = 0;
        Node x = header;
        for (int i = level - 1; i >= 0; i--) {
            while (x.forward[i] != null && traversed + x.span[i] <= rank) {
                traversed += x.span[i];
                x = x.forward[i];
            }
            if (traversed == rank) {
                return x == header ? null : x;
            }
        }
        return null;
    }

    /** 없으면 null */
    public Node firstInRange(ScoreRange range) {
        if (!overlaps(range)) {
            return null;
        }
        Node x = header;
        for (int i = level - 1; i >= 0; i--) {
            while (x.forward[i] != null && !range.aboveMin(x.forward[i].score)) {
                x = x.forward[i];
            }
        }
        x = x.forward[0];
        return x != null && range.belowMax(x.score) ? x : null;
    }

    /** 없으면 null */
    public Node lastInRange(ScoreRange range) {
        if (!overlaps(range)) {
            return null;
        }
        Node x = header;
        for (int i = level - 1; i >= 0; i--) {
            while (x.forward[i] != null && range.belowMax(x.forward[i].score)) {
                x = x.forward[i];
            }
        }
        return x != header && range.aboveMin(x.score) ? x : null;
    }

    /** 가장 높은 노드의 층수 */
    public int level() {
        return level;
    }

    private boolean overlaps(ScoreRange range) {
        if (range.isEmpty() || tail == null) {
            return false;
        }
        return range.aboveMin(tail.score) && range.belowMax(header.forward[0].score);
    }

    private static boolean precedes(Node node, double score, byte[] member) {
        return node.score < score
                || (node.score == score && Arrays.compareUnsigned(node.member, member) < 0);
    }

    private static boolean isAt(Node node, double score, byte[] member) {
        return node.score == score && Arrays.equals(node.member, member);
    }

    private static int randomLevel() {
        int level = 1;
        while (level < MAX_LEVEL && ThreadLocalRandom.current().nextDouble() < LEVEL_UP_PROBABILITY) {
            level++;
        }
        return level;
    }
}
