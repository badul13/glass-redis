package glassredis.store;

import glassredis.observe.Event;
import glassredis.observe.Event.RemovalReason;
import glassredis.observe.EventBuffer;
import glassredis.observe.EventHub;
import glassredis.observe.EventRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpiryCycleTest {

    private static final Duration GENEROUS_BUDGET = Duration.ofSeconds(5);

    private final ManualClock clock = new ManualClock(1_000_000);
    private final Keyspace keyspace = new Keyspace(clock);

    @Test
    @DisplayName("만료된 키만 있으면 25% 규칙에 따라 반복해서 전부 지운다")
    void repeatsWhileMostSamplesAreExpired() {
        putKeys("expired", 1000, clock.millis() + 10);
        clock.advanceMillis(11);

        int expired = cycle(GENEROUS_BUDGET).run();

        assertEquals(1000, expired);
        assertEquals(0, keyspace.size());
    }

    @Test
    @DisplayName("아직 만료되지 않은 키는 지우지 않는다")
    void keepsLiveKeys() {
        putKeys("live", 1000, clock.millis() + 10_000);

        assertEquals(0, cycle(GENEROUS_BUDGET).run());
        assertEquals(1000, keyspace.size());
    }

    @Test
    @DisplayName("만료된 키가 조금만 섞여 있으면 일부만 치우고 멈춘다 — 놓친 키도 읽을 때는 없는 키로 보인다")
    void stopsEarlyWhenFewAreExpired() {
        putKeys("live", 900, clock.millis() + 10_000);
        putKeys("expired", 100, clock.millis() + 10);
        clock.advanceMillis(11);

        int expired = cycle(GENEROUS_BUDGET).run();

        // 표본의 약 10% 만 만료돼 있으므로 25% 기준에 못 미쳐 금방 멈춘다.
        assertTrue(expired < 100, "전부 치우지 않고 멈춰야 한다. 지운 수: " + expired);
        assertEquals(1000 - expired, keyspace.size());

        // 샘플링이 놓친 키가 메모리에 남아 있어도, 읽는 순간 만료가 확인되므로 틀린 값이 보이지는 않는다.
        for (int i = 0; i < 100; i++) {
            assertNull(keyspace.get(Key.of("expired:" + i)));
        }
        assertEquals(900, keyspace.size());
    }

    @Test
    @DisplayName("제한 시간을 넘기면 치울 게 남아 있어도 다음 주기로 미룬다")
    void respectsTimeBudget() {
        putKeys("expired", 1000, clock.millis() + 10);
        clock.advanceMillis(11);

        // 제한 시간 0: 한 바퀴(20개)만 돌고 멈춘다.
        int expired = cycle(Duration.ZERO).run();

        assertEquals(ExpiryCycle.SAMPLE_SIZE, expired);
        assertEquals(1000 - ExpiryCycle.SAMPLE_SIZE, keyspace.size());
    }

    @Test
    @DisplayName("샘플링이 지운 키는 읽다가 지워진 것과 다른 이유로 알린다")
    void publishesActiveExpiry() {
        EventHub hub = new EventHub();
        EventBuffer screen = hub.subscribe();
        Keyspace observed = observedKeyspace(hub, 40);

        int expired = new ExpiryCycle(observed, new SplittableRandom(42), GENEROUS_BUDGET, hub).run();

        assertEquals(40, expired);
        List<EventRecord> drained = screen.drain(1000);
        Event.KeyRemoved first = (Event.KeyRemoved) drained.get(0).event();
        assertEquals(RemovalReason.ACTIVE_EXPIRED, first.reason(),
                "아무도 읽지 않았는데 사라졌다면 샘플링이 지운 것이다");
    }

    @Test
    @DisplayName("주기가 끝나면 몇 바퀴 돌며 몇 개를 뽑아 몇 개를 지웠는지 알린다")
    void publishesCycleSummary() {
        EventHub hub = new EventHub();
        EventBuffer screen = hub.subscribe();
        Keyspace observed = observedKeyspace(hub, 40);

        new ExpiryCycle(observed, new SplittableRandom(42), GENEROUS_BUDGET, hub).run();

        // 주기 요약은 지운 키를 모두 알린 뒤 마지막에 나온다.
        List<EventRecord> drained = screen.drain(1000);
        Event.ExpiryCycleCompleted summary = (Event.ExpiryCycleCompleted) drained.get(drained.size() - 1).event();
        assertEquals(40, summary.expired());
        // 한 바퀴는 20개다. 40개가 전부 만료돼 있었으므로 25% 규칙에 걸려 최소 두 바퀴는 돈다.
        assertTrue(summary.rounds() >= 2, "돈 바퀴 수: " + summary.rounds());
        assertTrue(summary.sampled() >= 40, "뽑은 수: " + summary.sampled());
        assertTrue(summary.durationNanos() >= 0);
    }

    @Test
    @DisplayName("뽑을 키가 하나도 없는 주기는 알리지 않는다 — 100ms 마다 '할 일 없음'을 보내지 않는다")
    void publishesNothingWhenIdle() {
        EventHub hub = new EventHub();
        EventBuffer screen = hub.subscribe();
        Keyspace observed = new Keyspace(clock, hub);

        assertEquals(0, new ExpiryCycle(observed, new SplittableRandom(42), GENEROUS_BUDGET, hub).run());

        assertEquals(List.of(), screen.drain(10));
    }

    private Keyspace observedKeyspace(EventHub hub, int expiredKeys) {
        Keyspace observed = new Keyspace(clock, hub);
        for (int i = 0; i < expiredKeys; i++) {
            observed.put(Key.of("expired:" + i), new Entry("v".getBytes(StandardCharsets.UTF_8), clock.millis() + 10));
        }
        clock.advanceMillis(11);
        return observed;
    }

    private ExpiryCycle cycle(Duration budget) {
        return new ExpiryCycle(keyspace, new SplittableRandom(42), budget);
    }

    private void putKeys(String prefix, int count, long expireAtMillis) {
        for (int i = 0; i < count; i++) {
            keyspace.put(Key.of(prefix + ":" + i), new Entry("v".getBytes(StandardCharsets.UTF_8), expireAtMillis));
        }
    }
}
