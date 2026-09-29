package glassredis.store;

import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Sorted Set 의 순서를 담는 스킵 리스트. 실제 Redis 의 {@code zskiplist} 를 그대로 옮겼다.
 *
 * <p>정렬된 연결 리스트 위에 "건너뛰는 층"을 여러 겹 쌓은 구조다. 위층일수록 노드가 듬성듬성해서,
 * 찾을 때는 맨 위층에서 크게 건너뛰다가 지나치기 직전에 한 층씩 내려온다.
 * <pre>
 *   3층  H ─────────────────────────▶ 40 ─────────────▶ nil
 *   2층  H ─────────▶ 20 ───────────▶ 40 ─────────────▶ nil
 *   1층  H ──▶ 10 ──▶ 20 ──▶ 30 ────▶ 40 ──▶ 50 ──────▶ nil
 * </pre>
 * 노드마다 몇 층까지 올라갈지는 동전 던지기로 정한다(한 층 더 올라갈 확률 1/4).
 * 그래서 균형을 맞추는 회전 같은 게 없는데도 평균 O(log n) 이 나온다.
 * 균형 트리 대신 이걸 쓴 이유로 Redis 작성자는 "구현이 단순하고, 구간 조회가 연결 리스트 따라가기라 쉽다"를 꼽았다.
 *
 * <p>Redis 판에는 교과서 스킵 리스트에 없는 게 두 가지 붙어 있고, 여기서도 따라 한다.
 * <ul>
 *   <li><b>span</b> — 각 화살표가 1층 기준으로 노드 몇 개를 건너뛰는지. 내려오면서 지나온 span 을 더하면
 *       그게 순위다. 그래서 {@code ZRANK} 와 "n 번째 원소 찾기"가 O(log n) 이다. 이게 없으면 순위를 세려고
 *       1층을 처음부터 걸어야 한다.</li>
 *   <li><b>backward</b> — 1층에만 있는 뒤로 가는 화살표. {@code ZREVRANGE} 가 거꾸로 걷는 데 쓴다.</li>
 * </ul>
 *
 * <p>순서는 점수가 먼저고, 점수가 같으면 멤버 바이트를 사전순(부호 없는 바이트 비교)으로 가른다.
 * 같은 점수에서도 순서가 하나로 정해져야 순위가 매번 같게 나온다.
 *
 * <p>멤버가 이미 있는지는 여기서 확인하지 않는다. 그건 {@link SortedSetValue} 가 해시로 먼저 본다.
 */
public final class SkipList {

    /** 층수 상한. 2^64 개를 담아도 넉넉한 높이다. 실제 Redis 와 같다. */
    static final int MAX_LEVEL = 32;

    /** 한 층 더 올라갈 확률. 1/2 이 아니라 1/4 이라 노드당 화살표 수가 평균 1.33개로 줄어든다. */
    static final double LEVEL_UP_PROBABILITY = 0.25;

    /** 노드 하나. 멤버와 점수는 들어간 뒤로 바뀌지 않는다 — 점수를 바꾸려면 지웠다가 다시 넣는다. */
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

        /** 1층의 다음 노드. 마지막이면 {@code null}. */
        public Node next() {
            return forward[0];
        }

        /** 1층의 앞 노드. 처음이면 {@code null}. */
        public Node previous() {
            return backward;
        }

        /** 이 노드가 올라가 있는 층수. */
        public int level() {
            return forward.length;
        }

        /**
         * {@code i} 층 화살표가 건너뛰는 노드 수. 그 층에서 이 노드가 끝이면(화살표가 nil 을 가리키면) -1.
         *
         * <p>nil 을 가리키는 화살표에도 span 값은 들어 있지만 의미가 없다. 순위를 셀 때
         * nil 쪽으로는 건너가지 않기 때문이다. 화면에 헷갈리는 숫자를 내보내지 않으려고 -1 로 가린다.
         */
        public long span(int i) {
            return forward[i] == null ? -1 : span[i];
        }
    }

    /** 원소를 담지 않는 머리 노드. 모든 층의 출발점이다. */
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

    /** 머리 노드의 {@code i} 층 화살표가 건너뛰는 노드 수. 규칙은 {@link Node#span} 과 같다. */
    public long headerSpan(int i) {
        return header.span(i);
    }

    /**
     * 넣는다. 같은 멤버가 이미 들어 있지 않다는 건 부르는 쪽이 보장한다.
     * 넣고 빼는 건 패키지 안({@link SortedSetValue})에서만 한다. 해시와 어긋나지 않게 하려는 것이다.
     */
    void insert(double score, byte[] member) {
        Node[] update = new Node[MAX_LEVEL];
        long[] rank = new long[MAX_LEVEL];

        // 층마다 "새 노드의 바로 앞이 될 노드"(update)와 그 노드의 순위(rank)를 찾아둔다.
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
            // 처음 쓰는 층은 머리에서 바로 끝(nil)으로 가는 화살표 하나뿐이다. 그 화살표는 전체를 건너뛴다.
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
            // 앞 노드의 화살표를 새 노드 자리에서 둘로 쪼갠다. rank[0] - rank[i] 는
            // i 층의 앞 노드와 1층의 앞 노드 사이에 있는 노드 수다.
            x.span[i] = update[i].span[i] - (rank[0] - rank[i]);
            update[i].span[i] = (rank[0] - rank[i]) + 1;
        }
        // 새 노드보다 높은 층의 화살표는 새 노드를 넘어가므로 건너뛰는 수가 하나 늘어난다.
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

    /** 지웠으면 {@code true}. 그 점수와 멤버의 노드가 없으면 {@code false}. */
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

    /** update[i] 는 i 층에서 x 바로 앞의 노드다(zslDeleteNode). */
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

    /**
     * 점수를 바꾼다(zslUpdateScore). 바뀐 점수로도 앞뒤 노드 사이에 그대로 있을 수 있으면 노드를 건드리지 않고
     * 점수만 고친다 — 층수도 그대로다. 자리가 바뀌어야 하면 지우고 새로 넣는데, 이때 층수는 다시 뽑는다.
     */
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
        // 비교가 < 와 > 로 엄격하다. 점수가 이웃과 같아지면 멤버 순서까지 봐야 하니 그냥 다시 넣는다.
        if ((x.backward == null || x.backward.score < newScore)
                && (x.forward[0] == null || x.forward[0].score > newScore)) {
            x.score = newScore;
            return;
        }
        deleteNode(x, update);
        insert(newScore, member);
    }

    /** 1부터 세는 순위. 없으면 0. 내려오면서 지나온 span 을 더한 게 순위다. */
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

    /** 1부터 세는 순위의 노드. 범위 밖이면 {@code null}. 위층에서 span 을 보고 크게 건너뛴다. */
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

    /** 구간에 드는 첫 노드. 없으면 {@code null}. */
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

    /** 구간에 드는 마지막 노드. 없으면 {@code null}. */
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

    /** 지금 쓰고 있는 층수. 가장 높은 노드의 층수와 같다. */
    public int level() {
        return level;
    }

    /** 구간이 이 리스트의 최솟값~최댓값과 조금이라도 겹치는지. 안 겹치면 걸어볼 필요도 없다. */
    private boolean overlaps(ScoreRange range) {
        if (range.isEmpty() || tail == null) {
            return false;
        }
        return range.aboveMin(tail.score) && range.belowMax(header.forward[0].score);
    }

    /** 노드가 (score, member) 보다 앞에 오는지. */
    private static boolean precedes(Node node, double score, byte[] member) {
        return node.score < score
                || (node.score == score && Arrays.compareUnsigned(node.member, member) < 0);
    }

    private static boolean isAt(Node node, double score, byte[] member) {
        return node.score == score && Arrays.equals(node.member, member);
    }

    /**
     * 새 노드의 층수. 1/4 확률로 한 층씩 더 올라간다.
     *
     * <p>{@code ThreadLocalRandom} 을 쓴다. 이 코드는 실행 스레드 하나에서만 도니 스레드별 난수기로 충분하고,
     * Sorted Set 마다 난수기를 하나씩 들고 있을 필요가 없다.
     */
    private static int randomLevel() {
        int level = 1;
        while (level < MAX_LEVEL && ThreadLocalRandom.current().nextDouble() < LEVEL_UP_PROBABILITY) {
            level++;
        }
        return level;
    }
}
