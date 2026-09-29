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
    @DisplayName("노드를 순서대로 저장 - span 을 따라가면 모든 화살표가 실제 노드에 도달")
    void spansLandOnNodes() {
        // 예상 개수가 크면 처음부터 skiplist 로 생성
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
        // 머리는 0번, 노드는 1..n번 자리
        assertLandsInside(0, snapshot.header(), 30);
        for (int i = 0; i < snapshot.nodes().size(); i++) {
            assertLandsInside(i + 1, snapshot.nodes().get(i).spans(), 30);
        }
        assertEquals(1L, snapshot.header().get(0));
        assertEquals(-1L, snapshot.nodes().get(29).spans().get(0));
    }

    @Test
    @DisplayName("listpack 이면 층 없이 멤버를 점수순으로 저장, 바이트 크기도 포함")
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
    @DisplayName("노드 수 상한 초과 시 앞부분만 유지, 멤버 수는 전체 기준")
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
    @DisplayName("없는 키와 다른 자료형은 상태로 통지")
    void missingAndWrongType() {
        keyspace.put(Key.of("s"), Entry.of("v".getBytes()));

        assertEquals("missing", SortedSetSnapshot.of(keyspace, Key.of("nope"), 10).status());
        assertEquals("wrongType", SortedSetSnapshot.of(keyspace, Key.of("s"), 10).status());
    }

    @Test
    @DisplayName("만료된 키 조회 시 삭제 없음 - 관측이 상태를 바꾸면 안 됨")
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
