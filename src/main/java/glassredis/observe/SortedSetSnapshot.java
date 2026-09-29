package glassredis.observe;

import glassredis.store.Entry;
import glassredis.store.Key;
import glassredis.store.Keyspace;
import glassredis.store.SkipList;
import glassredis.store.SortedSetValue;

import java.util.ArrayList;
import java.util.List;

/**
 * Sorted Set 하나의 내부 구조 스냅샷 - skiplist는 층별 span만
 * 화살표 끝 = k번째 노드에서 span만큼 건너간 노드
 *
 * @param status   ok, 키 없으면 missing, Sorted Set 아니면 wrongType
 * @param encoding listpack 또는 skiplist
 * @param length   전체 멤버 수 - 목록이 잘려도 전체 기준
 * @param bytes    listpack이면 바이트 배열 전체 크기, skiplist면 0
 * @param level    skiplist 사용 층수 - listpack이면 0
 * @param header   머리 노드의 층별 span - listpack이면 빈 목록
 * @param nodes    점수순 - DEFAULT_MAX_NODES개까지
 */
public record SortedSetSnapshot(String key, String status, String encoding, int length, int bytes, int level,
                                List<Long> header, List<NodeView> nodes) {

    /** 잘린 너머를 가리키는 화살표 - 화면에서 오른쪽 끝으로 표시 */
    public static final int DEFAULT_MAX_NODES = 40;

    /** @param spans 층별 span - 그 층 마지막 노드면 -1, 길이 = 층수, listpack이면 빈 목록 */
    public record NodeView(String member, double score, List<Long> spans) {

        public NodeView {
            spans = List.copyOf(spans);
        }
    }

    public SortedSetSnapshot {
        header = List.copyOf(header);
        nodes = List.copyOf(nodes);
    }

    /** 실행 스레드 전용 - 만료 처리 회피용 Keyspace.peek 조회 */
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
