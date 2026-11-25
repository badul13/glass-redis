package glassredis.store;

import glassredis.observe.Event;
import glassredis.observe.Event.RemovalReason;
import glassredis.observe.EventBuffer;
import glassredis.observe.EventHub;
import glassredis.observe.EventRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
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
    @DisplayName("delete - 실제로 지웠을 때만 true (dbSyncDelete)")
    void deleteReportsWhetherKeyExisted() {
        keyspace.put(Key.of("k"), Entry.of(bytes("v")));

        assertTrue(keyspace.delete(Key.of("k")));
        assertFalse(keyspace.delete(Key.of("k")));
        assertNull(keyspace.get(Key.of("k")));
    }

    @Test
    @DisplayName("만료 시각과 같은 ms 에는 유효, 1ms 뒤 조회 시 없는 키 취급 후 즉시 삭제")
    void lazyExpiry() {
        keyspace.put(Key.of("k"), Entry.of(bytes("v")).withExpireAt(clock.millis() + 100));

        advance(100);
        assertNotNull(keyspace.get(Key.of("k")));

        advance(1);
        assertEquals(1, keyspace.size(), "읽기 전까지 메모리에 남은 키");
        assertNull(keyspace.get(Key.of("k")));
        assertEquals(0, keyspace.size());
        assertEquals(0, keyspace.expiringKeyCount());
    }

    @Test
    @DisplayName("DEL 순서 - expireIfNeeded 가 먼저 지우므로 만료 키는 삭제 집계 제외")
    void delOfExpiredKeyCountsNothing() {
        keyspace.put(Key.of("k"), Entry.of(bytes("v")).withExpireAt(clock.millis() + 10));
        advance(11);

        assertTrue(keyspace.expireIfNeeded(Key.of("k")));
        assertFalse(keyspace.delete(Key.of("k")));
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
        advance(130);
        assertNull(observed.get(Key.of("k")));

        Event.KeyRemoved removed = onlyKeyRemoved();
        assertEquals("k", removed.key());
        assertEquals(RemovalReason.LAZY_EXPIRED, removed.reason());
        assertEquals(30, removed.lateByMillis(), "만료 시각 대비 삭제 지연 30ms");
    }

    @Test
    @DisplayName("DEL 로 지운 것은 만료와 다른 이유로 통지")
    void publishesDeletion() {
        observed.put(Key.of("k"), Entry.of(bytes("v")));

        assertTrue(observed.delete(Key.of("k")));

        Event.KeyRemoved removed = onlyKeyRemoved();
        assertEquals(RemovalReason.DELETED, removed.reason());
        assertEquals(0, removed.lateByMillis(), "만료 삭제 아님 - 지연 0");
    }

    @Test
    @DisplayName("없는 키 삭제와 값 쓰기는 통지 제외")
    void publishesNothingWithoutRemoval() {
        observed.put(Key.of("k"), Entry.of(bytes("v")));
        assertNull(observed.get(Key.of("nope")));
        assertFalse(observed.delete(Key.of("nope")));

        assertEquals(List.of(), screen.drain(10));
    }

    @Test
    @DisplayName("만료 판정 시각은 명령 시작 스냅샷 - 시계가 흘러도 같은 명령 안에서는 판정 불변 (commandTimeSnapshot)")
    void expiryUsesCommandTimeSnapshot() {
        TickingClock ticking = new TickingClock(1_000_000);
        Keyspace frozen = new Keyspace(ticking);
        frozen.put(Key.of("k"), Entry.of(bytes("v")).withExpireAt(1_000_005));

        // 읽을 때마다 1ms 흐르는 시계 - 스냅샷 없이 매번 시계를 보면 몇 번 뒤 만료
        for (int i = 0; i < 20; i++) {
            assertNotNull(frozen.get(Key.of("k")), (i + 1) + "번째 조회에서 만료");
        }

        // 다음 명령 시작(call) 때 스냅샷 갱신 - 그때부터 만료
        ticking.skip(10);
        frozen.updateCommandTime();
        assertNull(frozen.get(Key.of("k")));
    }

    @Test
    @DisplayName("isExpired(keyIsExpired) - 삭제 없이 스냅샷 기준 판정, 로딩 중엔 항상 false")
    void isExpiredUsesSnapshotAndIgnoresLoading() {
        keyspace.put(Key.of("k"), Entry.of(bytes("v")).withExpireAt(clock.millis() + 10));
        clock.advanceMillis(20);
        assertFalse(keyspace.isExpired(Key.of("k")), "스냅샷 갱신 전");

        keyspace.updateCommandTime();
        assertTrue(keyspace.isExpired(Key.of("k")));
        assertEquals(1, keyspace.size(), "판정만 하고 삭제 없음");

        keyspace.setLoading(true);
        assertFalse(keyspace.isExpired(Key.of("k")));
    }

    @Test
    @DisplayName("읽기 전용 레플리카 - 읽기·쓰기 조회 모두 만료 키를 지우지 않고 없는 것으로만 보임")
    void readOnlyReplicaNeverDeletesExpiredKeys() {
        keyspace.put(Key.of("k"), Entry.of(bytes("v")).withExpireAt(clock.millis() + 10));
        keyspace.setReplica(true);
        advance(20);

        assertNull(keyspace.get(Key.of("k")));
        assertNull(keyspace.getWrite(Key.of("k")));
        assertEquals(1, keyspace.size(), "마스터의 DEL 을 기다리는 키");
    }

    @Test
    @DisplayName("쓰기 가능한 레플리카 - 읽기 조회는 남기고, 쓰기 조회는 실제 삭제 (EXPIRE_FORCE_DELETE_EXPIRED)")
    void writableReplicaDeletesOnWriteLookup() {
        List<Key> propagated = new ArrayList<>();
        keyspace.setExpiredListener(propagated::add);
        keyspace.put(Key.of("k"), Entry.of(bytes("v")).withExpireAt(clock.millis() + 10));
        keyspace.setReplica(true);
        keyspace.setReplicaReadOnly(false);
        advance(20);

        assertNull(keyspace.get(Key.of("k")));
        assertEquals(1, keyspace.size(), "읽기 조회로는 삭제 없음");

        assertNull(keyspace.getWrite(Key.of("k")));
        assertEquals(0, keyspace.size(), "쓰기 조회로 삭제");
        assertEquals(List.of(Key.of("k")), propagated, "삭제는 DEL 로 전파");
    }

    @Test
    @DisplayName("마스터 스트림 실행 중인 레플리카 - 쓰기 조회여도 만료 판정 자체를 안 함")
    void masterStreamSeesExpiredKeysAsAlive() {
        keyspace.put(Key.of("k"), Entry.of(bytes("v")).withExpireAt(clock.millis() + 10));
        keyspace.setReplica(true);
        keyspace.setReplicaReadOnly(false);
        keyspace.setMasterStream(true);
        advance(20);

        assertNotNull(keyspace.getWrite(Key.of("k")));
        assertNotNull(keyspace.get(Key.of("k")));
    }

    @Test
    @DisplayName("lru - 만들 때와 조회 때 지금 LRU 시계(초), NOTOUCH 조회는 그대로, 24비트 넘어가면 한 바퀴 보정")
    void lruTracksAccess() {
        // 1_000_000ms → LRU 시계 1000초
        keyspace.add(Key.of("k"), StringValue.of(bytes("hello")));
        assertEquals(0, keyspace.idleSeconds(Key.of("k")));

        clock.advanceMillis(5_000);
        keyspace.updateLruClock();
        assertNotNull(keyspace.getNoTouch(Key.of("k")));
        assertEquals(5, keyspace.idleSeconds(Key.of("k")), "NOTOUCH 조회는 lru 유지");

        assertNotNull(keyspace.get(Key.of("k")));
        assertEquals(0, keyspace.idleSeconds(Key.of("k")), "일반 조회는 lru 갱신");

        // 마지막 접근 lru 1005 초, LRU 시계가 2^24 초를 넘어 3 으로 돌아온 경우
        // estimateObjectIdleTime 보정식 - lruclock + (LRU_CLOCK_MAX - lru), 실제 경과보다 1초 적음까지 그대로
        clock.advanceMillis(((1L << 24) + 3 - 1005) * 1000);
        keyspace.updateLruClock();
        assertEquals(3 + (Keyspace.LRU_CLOCK_MAX - 1005), keyspace.idleSeconds(Key.of("k")));
    }

    @Test
    @DisplayName("자식 작업 중엔 조회해도 lru 그대로 - copy-on-write 회피 (hasActiveChildProcess)")
    void lruNotTouchedWhileChildActive() {
        boolean[] child = {true};
        keyspace.setChildActiveCheck(() -> child[0]);
        keyspace.add(Key.of("k"), StringValue.of(bytes("hello")));
        clock.advanceMillis(7_000);
        keyspace.updateLruClock();

        keyspace.get(Key.of("k"));
        assertEquals(7, keyspace.idleSeconds(Key.of("k")));

        child[0] = false;
        keyspace.get(Key.of("k"));
        assertEquals(0, keyspace.idleSeconds(Key.of("k")));
    }

    @Test
    @DisplayName("값 교체(dbSetValue)는 옛 값의 lru 를 새 값에 - 공유 정수로 바꾸면 공유 lru 를 덮어씀")
    void replaceKeepsOldLru() {
        keyspace.add(Key.of("k"), StringValue.of(bytes("hello")));
        clock.advanceMillis(9_000);
        keyspace.updateLruClock();

        keyspace.replaceValue(Key.of("k"), StringValue.of(bytes("world")));
        assertEquals(9, keyspace.idleSeconds(Key.of("k")), "옛 값의 lru 유지");

        // 공유 정수 7 의 lru 가 k 의 옛 lru 로 - 같은 공유 객체를 가진 다른 키도 같이 보임
        keyspace.add(Key.of("other"), StringValue.of(bytes("7")));
        keyspace.replaceValue(Key.of("k"), StringValue.of(bytes("7")));
        assertEquals(9, keyspace.idleSeconds(Key.of("other")));
    }

    /** millis() 를 부를 때마다 1ms 흐르는 시계 */
    private static final class TickingClock implements InstantSource {

        private long millis;

        TickingClock(long startMillis) {
            this.millis = startMillis;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis());
        }

        @Override
        public long millis() {
            return millis++;
        }

        void skip(long amount) {
            millis += amount;
        }
    }

    /** 시계를 돌리고 다음 명령 시작처럼 시각 스냅샷 갱신 */
    private void advance(long millis) {
        clock.advanceMillis(millis);
        keyspace.updateCommandTime();
        observed.updateCommandTime();
    }

    private Event.KeyRemoved onlyKeyRemoved() {
        List<EventRecord> drained = screen.drain(10);
        assertEquals(1, drained.size(), "이벤트 하나 기대: " + drained);
        return (Event.KeyRemoved) drained.get(0).event();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
