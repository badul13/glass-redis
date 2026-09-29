package glassredis.store;

import glassredis.observe.Event;
import glassredis.observe.Event.RemovalReason;
import glassredis.observe.EventBuffer;
import glassredis.observe.EventBus;
import glassredis.observe.EventHub;
import glassredis.observe.EventRecord;
import glassredis.store.ActiveExpireCycle.Kind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActiveExpireCycleTest {

    private final ManualClock clock = new ManualClock(1_000_000);
    private final Keyspace keyspace = new Keyspace(clock);
    private final ActiveExpireCycle cycle = new ActiveExpireCycle(keyspace, EventBus.NONE);

    @Test
    @DisplayName("만료된 키만 있으면 10% 규칙에 따라 반복하며 전부 삭제")
    void repeatsWhileMostAreExpired() {
        putKeys("dead", 200, clock.millis() + 10);
        clock.advanceMillis(20);

        assertEquals(200, cycle.run(Kind.SLOW));
        assertEquals(0, keyspace.size());
    }

    @Test
    @DisplayName("아직 만료되지 않은 키는 유지")
    void keepsLiveKeys() {
        putKeys("live", 100, clock.millis() + 60_000);

        assertEquals(0, cycle.run(Kind.SLOW));
        assertEquals(100, keyspace.size());
    }

    @Test
    @DisplayName("만료된 키가 조금만 섞여 있으면 다 치우기 전에 중단 - 놓친 키도 읽을 때는 없는 키 취급")
    void stopsEarlyWhenFewAreExpired() {
        putKeys("live", 1000, clock.millis() + 60_000);
        putKeys("dead", 20, clock.millis() + 10);
        clock.advanceMillis(20);

        int expired = cycle.run(Kind.SLOW);

        assertTrue(expired < 20, "한 번에 다 치웠다: " + expired);
        for (int i = 0; i < 20; i++) {
            assertNull(keyspace.get(Key.of("dead:" + i)));
        }
    }

    @Test
    @DisplayName("커서가 이어지므로 여러 번 돌면 만료된 키 누락 없음")
    void cursorEventuallyCoversEverything() {
        putKeys("live", 1000, clock.millis() + 60_000);
        putKeys("dead", 20, clock.millis() + 10);
        clock.advanceMillis(20);

        // 1회 약 20개 확인, 1020개 한 바퀴 ≈ 50회 - 여유 있게 100회
        for (int i = 0; i < 100; i++) {
            cycle.run(Kind.SLOW);
        }

        assertEquals(1000, keyspace.size());
    }

    @Test
    @DisplayName("FAST - 치울 게 별로 없어 보이면 실행 생략")
    void fastSkipsWhenLittleIsStale() {
        putKeys("dead", 50, clock.millis() + 10);
        clock.advanceMillis(20);

        assertEquals(0, cycle.run(Kind.FAST));
        assertEquals(50, keyspace.size());
    }

    @Test
    @DisplayName("키가 한꺼번에 많이 만료되면 SLOW 는 25ms 한도에서 남은 일 보류")
    void slowRespectsTimeLimit() {
        EventHub hub = new EventHub();
        EventBuffer screen = hub.subscribe();
        Keyspace observed = new Keyspace(clock, hub);
        ActiveExpireCycle observedCycle = new ActiveExpireCycle(observed, hub);
        for (int i = 0; i < 200_000; i++) {
            observed.put(Key.of("dead:" + i), Entry.of(bytes("v")).withExpireAt(clock.millis() + 10));
        }
        clock.advanceMillis(20);

        observedCycle.run(Kind.SLOW);

        Event.ExpiryCycleCompleted summary = lastCycle(screen);
        assertTrue(summary.timeLimitHit(), "시간 한도에 걸리지 않았다");
        assertTrue(observed.size() > 0, "한 번에 다 치웠다");
    }

    @Test
    @DisplayName("주기적 만료로 지운 키는 읽기 중 삭제와 다른 이유로 통지, 주기 요약도 통지")
    void publishesRemovalsAndSummary() {
        EventHub hub = new EventHub();
        EventBuffer screen = hub.subscribe();
        Keyspace observed = new Keyspace(clock, hub);
        observed.put(Key.of("k"), Entry.of(bytes("v")).withExpireAt(clock.millis() + 10));
        clock.advanceMillis(50);

        new ActiveExpireCycle(observed, hub).run(Kind.SLOW);

        List<EventRecord> records = screen.drain(10);
        Event.KeyRemoved removed = (Event.KeyRemoved) records.get(0).event();
        assertEquals(RemovalReason.ACTIVE_EXPIRED, removed.reason());
        assertEquals(40, removed.lateByMillis());
        Event.ExpiryCycleCompleted summary = (Event.ExpiryCycleCompleted) records.get(1).event();
        assertEquals("SLOW", summary.kind());
        assertEquals(1, summary.sampled());
        assertEquals(1, summary.expired());
    }

    @Test
    @DisplayName("볼 키가 하나도 없는 주기는 통지 생략")
    void publishesNothingWhenIdle() {
        EventHub hub = new EventHub();
        EventBuffer screen = hub.subscribe();

        new ActiveExpireCycle(new Keyspace(clock, hub), hub).run(Kind.SLOW);

        assertTrue(screen.drain(10).isEmpty());
    }

    private static Event.ExpiryCycleCompleted lastCycle(EventBuffer screen) {
        Event.ExpiryCycleCompleted last = null;
        for (List<EventRecord> batch = screen.drain(10_000); !batch.isEmpty(); batch = screen.drain(10_000)) {
            for (EventRecord record : batch) {
                if (record.event() instanceof Event.ExpiryCycleCompleted cycleEvent) {
                    last = cycleEvent;
                }
            }
        }
        return last;
    }

    private void putKeys(String prefix, int count, long expireAtMillis) {
        for (int i = 0; i < count; i++) {
            keyspace.put(Key.of(prefix + ":" + i), Entry.of(bytes("v")).withExpireAt(expireAtMillis));
        }
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
