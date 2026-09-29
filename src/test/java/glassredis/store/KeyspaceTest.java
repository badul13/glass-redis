package glassredis.store;

import glassredis.observe.Event;
import glassredis.observe.Event.RemovalReason;
import glassredis.observe.EventBuffer;
import glassredis.observe.EventHub;
import glassredis.observe.EventRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyspaceTest {

    private final ManualClock clock = new ManualClock(1_000_000);
    private final Keyspace keyspace = new Keyspace(clock);

    private final EventHub hub = new EventHub();
    private final EventBuffer screen = hub.subscribe();
    private final Keyspace observed = new Keyspace(clock, hub);

    @Test
    @DisplayName("내용이 같으면 다른 배열로도 같은 키 조회")
    void keysCompareByContent() {
        // 명령마다 키 배열이 새로 만들어지는 상황 재현
        byte[] written = bytes("foo");
        byte[] lookedUp = bytes("foo");
        assertNotSame(written, lookedUp);

        keyspace.put(new Key(written), Entry.of(bytes("bar")));

        assertArrayEquals(bytes("bar"), ((StringValue) keyspace.get(new Key(lookedUp)).value()).bytes());
    }

    @Test
    @DisplayName("없는 키는 null")
    void missingKey() {
        assertNull(keyspace.get(Key.of("nope")));
    }

    @Test
    @DisplayName("remove - 실제로 지웠을 때만 true")
    void removeReportsWhetherKeyExisted() {
        keyspace.put(Key.of("k"), Entry.of(bytes("v")));

        assertTrue(keyspace.remove(Key.of("k")));
        assertFalse(keyspace.remove(Key.of("k")));
        assertNull(keyspace.get(Key.of("k")));
    }

    @Test
    @DisplayName("만료 시각과 같은 ms 에는 유효, 1ms 뒤 조회 시 없는 키 취급 후 즉시 삭제")
    void lazyExpiry() {
        keyspace.put(Key.of("k"), Entry.of(bytes("v")).withExpireAt(clock.millis() + 100));

        clock.advanceMillis(100);
        assertNotNull(keyspace.get(Key.of("k")));

        clock.advanceMillis(1);
        assertEquals(1, keyspace.size(), "읽기 전까지는 메모리에 남아 있다");
        assertNull(keyspace.get(Key.of("k")));
        assertEquals(0, keyspace.size());
        assertEquals(0, keyspace.expiringKeyCount());
    }

    @Test
    @DisplayName("이미 만료된 키는 remove 해도 삭제 집계 제외")
    void removeExpiredKey() {
        keyspace.put(Key.of("k"), Entry.of(bytes("v")).withExpireAt(clock.millis() + 10));
        clock.advanceMillis(11);

        assertFalse(keyspace.remove(Key.of("k")));
    }

    @Test
    @DisplayName("만료 시각 없는 값으로 덮어쓰면 샘플링 대상에서 제외")
    void overwriteWithoutExpiryLeavesSamplingSet() {
        keyspace.put(Key.of("k"), Entry.of(bytes("v")).withExpireAt(clock.millis() + 100));
        assertEquals(1, keyspace.expiringKeyCount());

        keyspace.put(Key.of("k"), Entry.of(bytes("v2")));
        assertEquals(0, keyspace.expiringKeyCount());
    }

    @Test
    @DisplayName("읽기 중 만료 발견 후 삭제 시 만료 시각 대비 지연 시간까지 통지")
    void publishesLazyExpiry() {
        observed.put(Key.of("k"), Entry.of(bytes("v")).withExpireAt(clock.millis() + 100));

        // 100ms 짜리 키를 130ms 뒤에 조회 - 읽기 전까지는 잔존
        clock.advanceMillis(130);
        assertNull(observed.get(Key.of("k")));

        Event.KeyRemoved removed = onlyKeyRemoved();
        assertEquals("k", removed.key());
        assertEquals(RemovalReason.LAZY_EXPIRED, removed.reason());
        assertEquals(30, removed.lateByMillis(), "만료 시각보다 30ms 늦게 지워졌다");
    }

    @Test
    @DisplayName("DEL 로 지운 것은 만료와 다른 이유로 통지")
    void publishesDeletion() {
        observed.put(Key.of("k"), Entry.of(bytes("v")));

        assertTrue(observed.remove(Key.of("k")));

        Event.KeyRemoved removed = onlyKeyRemoved();
        assertEquals(RemovalReason.DELETED, removed.reason());
        assertEquals(0, removed.lateByMillis(), "만료로 지워진 게 아니므로 늦은 시간이 없다");
    }

    @Test
    @DisplayName("없는 키 삭제와 값 쓰기는 통지 제외")
    void publishesNothingWithoutRemoval() {
        observed.put(Key.of("k"), Entry.of(bytes("v")));
        assertNull(observed.get(Key.of("nope")));
        assertFalse(observed.remove(Key.of("nope")));

        assertEquals(List.of(), screen.drain(10));
    }

    private Event.KeyRemoved onlyKeyRemoved() {
        List<EventRecord> drained = screen.drain(10);
        assertEquals(1, drained.size(), "이벤트가 하나만 나와야 한다: " + drained);
        return (Event.KeyRemoved) drained.get(0).event();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
