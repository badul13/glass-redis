package glassredis.observe;

import glassredis.store.Entry;
import glassredis.store.Key;
import glassredis.store.Keyspace;
import glassredis.store.SkipList;
import glassredis.store.SortedSetValue;

import java.util.ArrayList;
import java.util.List;

/**
 * 어느 한 순간의 Sorted Set 하나의 속 모양. 대시보드의 "Sorted Set 내부 구조" 패널이 이걸 그린다.
 *
 * <p>인코딩에 따라 담는 게 다르다.
 * <ul>
 *   <li><b>listpack</b> — 멤버를 점수순으로 담고, 바이트 배열 전체 크기를 함께 담는다. 층도 화살표도 없다.</li>
 *   <li><b>skiplist</b> — 층과 span 을 담는다. 화살표가 어느 노드를 가리키는지는 따로 담지 않는다 —
 *       노드가 순서대로 늘어서 있으니 "k 번째 노드에서 span 만큼 건너간 곳"이 곧 화살표 끝이다.
 *       실제 Redis 가 순위를 셀 때 쓰는 것도 이 정보뿐이다.</li>
 * </ul>
 *
 * @param status   {@code ok}, 키가 없으면 {@code missing}, Sorted Set 이 아니면 {@code wrongType}
 * @param encoding {@code listpack} 또는 {@code skiplist}
 * @param length   멤버 수. 아래 목록이 잘렸어도 전체를 센 것이다.
 * @param bytes    listpack 이면 바이트 배열 전체 크기, skiplist 면 0
 * @param level    skiplist 가 쓰고 있는 층수. listpack 이면 0
 * @param header   머리 노드의 층별 span. listpack 이면 비어 있다.
 * @param nodes    점수순. {@link #DEFAULT_MAX_NODES} 개까지만.
 */
public record SortedSetSnapshot(String key, String status, String encoding, int length, int bytes, int level,
                                List<Long> header, List<NodeView> nodes) {

    /**
     * 그리는 노드 수의 상한. 노드 하나가 화면 폭을 한 칸씩 먹으므로 이보다 많으면 어차피 읽을 수 없다.
     * 목록이 잘리면 그 너머를 가리키는 화살표는 화면 오른쪽 끝으로 그린다.
     */
    public static final int DEFAULT_MAX_NODES = 40;

    /**
     * 멤버 하나.
     *
     * @param spans 층별 span. 그 층에서 이 노드가 끝이면 -1. 길이가 곧 이 노드의 층수다. listpack 이면 비어 있다.
     */
    public record NodeView(String member, double score, List<Long> spans) {

        public NodeView {
            spans = List.copyOf(spans);
        }
    }

    public SortedSetSnapshot {
        header = List.copyOf(header);
        nodes = List.copyOf(nodes);
    }

    /**
     * 실행 스레드에서 부른다. 키는 {@link Keyspace#peek} 로 본다 — 들여다보는 것만으로 만료 처리가 일어나면 안 된다.
     */
    public static SortedSetSnapshot of(Keyspace keyspace, Key key, int maxNodes) {
        String name = Display.text(key.bytes());
        Entry entry = keyspace.peek(key);
        if (entry == null) {
            return new SortedSetSnapshot(name, "missing", "", 0, 0, 0, List.of(), List.of());
        }
        if (!(entry.value() instanceof SortedSetValue zset)) {
            return new SortedSetSnapshot(name, "wrongType", "", 0, 0, 0, List.of(), List.of());
        }

        if (zset.listpack() != null) {
            List<NodeView> nodes = new ArrayList<>();
            zset.forEachInRankRange(0, Math.min(zset.size(), maxNodes) - 1, false,
                    (member, score) -> nodes.add(new NodeView(Display.text(member), score, List.of())));
            return new SortedSetSnapshot(name, "ok", "listpack", zset.size(), zset.listpack().bytes(), 0,
                    List.of(), nodes);
        }

        SkipList order = zset.order();
        List<Long> header = new ArrayList<>(order.level());
        for (int i = 0; i < order.level(); i++) {
            header.add(order.headerSpan(i));
        }
        List<NodeView> nodes = new ArrayList<>();
        for (SkipList.Node node = order.first(); node != null && nodes.size() < maxNodes; node = node.next()) {
            List<Long> spans = new ArrayList<>(node.level());
            for (int i = 0; i < node.level(); i++) {
                spans.add(node.span(i));
            }
            nodes.add(new NodeView(Display.text(node.member()), node.score(), spans));
        }
        return new SortedSetSnapshot(name, "ok", "skiplist", order.length(), 0, order.level(), header, nodes);
    }
}
