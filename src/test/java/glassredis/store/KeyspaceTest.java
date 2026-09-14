package glassredis.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

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

    @Test
    @DisplayName("내용이 같으면 다른 배열로도 같은 키를 찾는다")
    void keysCompareByContent() {
        // 명령마다 키 배열이 새로 만들어지는 상황을 흉내 낸다.
        byte[] written = bytes("foo");
        byte[] lookedUp = bytes("foo");
        assertNotSame(written, lookedUp);

        keyspace.put(new Key(written), Entry.of(bytes("bar")));

        assertArrayEquals(bytes("bar"), keyspace.get(new Key(lookedUp)).value());
    }

    @Test
    @DisplayName("없는 키는 null 을 준다")
    void missingKey() {
        assertNull(keyspace.get(Key.of("nope")));
    }

    @Test
    @DisplayName("remove 는 실제로 지웠을 때만 true 다")
    void removeReportsWhetherKeyExisted() {
        keyspace.put(Key.of("k"), Entry.of(bytes("v")));

        assertTrue(keyspace.remove(Key.of("k")));
        assertFalse(keyspace.remove(Key.of("k")));
        assertNull(keyspace.get(Key.of("k")));
    }

    @Test
    @DisplayName("만료 시각과 같은 ms 에는 살아 있고, 1ms 뒤에 읽으면 없는 키가 되며 그 자리에서 지워진다")
    void lazyExpiry() {
        keyspace.put(Key.of("k"), new Entry(bytes("v"), clock.millis() + 100));

        clock.advanceMillis(100);
        assertNotNull(keyspace.get(Key.of("k")));

        clock.advanceMillis(1);
        assertEquals(1, keyspace.size(), "읽기 전까지는 메모리에 남아 있다");
        assertNull(keyspace.get(Key.of("k")));
        assertEquals(0, keyspace.size());
        assertEquals(0, keyspace.expiringKeyCount());
    }

    @Test
    @DisplayName("이미 만료된 키는 remove 해도 지운 것으로 세지 않는다")
    void removeExpiredKey() {
        keyspace.put(Key.of("k"), new Entry(bytes("v"), clock.millis() + 10));
        clock.advanceMillis(11);

        assertFalse(keyspace.remove(Key.of("k")));
    }

    @Test
    @DisplayName("만료 시각이 없는 값으로 덮어쓰면 샘플링 대상에서 빠진다")
    void overwriteWithoutExpiryLeavesSamplingSet() {
        keyspace.put(Key.of("k"), new Entry(bytes("v"), clock.millis() + 100));
        assertEquals(1, keyspace.expiringKeyCount());

        keyspace.put(Key.of("k"), Entry.of(bytes("v2")));
        assertEquals(0, keyspace.expiringKeyCount());
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
