package glassredis.observe;

import glassredis.store.Entry;
import glassredis.store.Key;
import glassredis.store.Keyspace;
import glassredis.store.ManualClock;
import glassredis.store.SortedSetValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SortedSetSnapshotTest {

    private final ManualClock clock = new ManualClock(1_000_000);
    private final Keyspace keyspace = new Keyspace(clock);

    @Test
    @DisplayName("노드를 순서대로 담고, span 을 따라가면 모든 화살표가 실제 노드에 떨어진다")
    void spansLandOnNodes() {
        // 넣을 개수를 크게 알려주면 처음부터 skiplist 로 만든다. 30개만 넣어도 층이 생긴다.
        SortedSetValue zset = SortedSetValue.create(200, 2);
        for (int i = 0; i < 30; i++) {
            zset.put(Key.of("m" + i), i);
        }
        keyspace.put(Key.of("z"), Entry.of(zset));

        SortedSetSnapshot snapshot = SortedSetSnapshot.of(keyspace, Key.of("z"), 100);

        assertEquals("ok", snapshot.status());
        assertEquals("skiplist", snapshot.encoding());
        assertEquals(30, snapshot.nodes().size());
        assertEquals("m0", snapshot.nodes().get(0).member());
        assertEquals(snapshot.level(), snapshot.header().size());
        // 머리(0번 자리)와 각 노드(1..n번 자리)에서 span 만큼 건너가면 1..n 사이에 떨어져야 한다.
        assertLandsInside(0, snapshot.header(), 30);
        for (int i = 0; i < snapshot.nodes().size(); i++) {
            assertLandsInside(i + 1, snapshot.nodes().get(i).spans(), 30);
        }
        // 1층은 빠짐없이 한 칸씩 이어진다. 마지막 노드만 끝이다.
        assertEquals(1L, snapshot.header().get(0));
        assertEquals(-1L, snapshot.nodes().get(29).spans().get(0));
    }

    @Test
    @DisplayName("listpack 이면 층 없이 멤버를 점수순으로 담고, 바이트 크기를 함께 담는다")
    void listpack() {
        SortedSetValue zset = SortedSetValue.create(3, 1);
        zset.put(Key.of("b"), 2);
        zset.put(Key.of("a"), 1);
        keyspace.put(Key.of("z"), Entry.of(zset));

        SortedSetSnapshot snapshot = SortedSetSnapshot.of(keyspace, Key.of("z"), 100);

        assertEquals("listpack", snapshot.encoding());
        assertEquals(0, snapshot.level());
        assertEquals("a", snapshot.nodes().get(0).member());
        assertEquals(List.of(), snapshot.nodes().get(0).spans());
        // 헤더 6 + [a 3바이트][1 2바이트][b 3바이트][2 2바이트] + 끝 1
        assertEquals(6 + 3 + 2 + 3 + 2 + 1, snapshot.bytes());
    }

    @Test
    @DisplayName("노드 수 상한을 넘으면 앞에서부터 자르고, 멤버 수는 전체를 센다")
    void truncates() {
        SortedSetValue zset = SortedSetValue.create(1, 2);
        for (int i = 0; i < 10; i++) {
            zset.put(Key.of("m" + i), i);
        }
        keyspace.put(Key.of("z"), Entry.of(zset));

        SortedSetSnapshot snapshot = SortedSetSnapshot.of(keyspace, Key.of("z"), 4);

        assertEquals(4, snapshot.nodes().size());
        assertEquals(10, snapshot.length());
    }

    @Test
    @DisplayName("없는 키와 다른 자료형은 상태로 알린다")
    void missingAndWrongType() {
        keyspace.put(Key.of("s"), Entry.of("v".getBytes()));

        assertEquals("missing", SortedSetSnapshot.of(keyspace, Key.of("nope"), 10).status());
        assertEquals("wrongType", SortedSetSnapshot.of(keyspace, Key.of("s"), 10).status());
    }

    @Test
    @DisplayName("만료된 키를 들여다봐도 지우지 않는다 — 보는 것만으로 서버 상태가 바뀌면 안 된다")
    void doesNotTriggerLazyExpiry() {
        SortedSetValue zset = SortedSetValue.create(1, 2);
        zset.put(Key.of("a"), 1);
        keyspace.put(Key.of("z"), Entry.of(zset).withExpireAt(clock.millis() + 10));
        clock.advanceMillis(20);

        SortedSetSnapshot.of(keyspace, Key.of("z"), 10);

        assertEquals(1, keyspace.size());
    }

    private static void assertLandsInside(int from, List<Long> spans, int length) {
        for (long span : spans) {
            if (span != -1) {
                long target = from + span;
                assertEquals(true, target >= 1 && target <= length, from + " 에서 " + span + " 건너면 " + target);
            }
        }
    }
}
