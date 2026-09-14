package glassredis.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
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

    private ExpiryCycle cycle(Duration budget) {
        return new ExpiryCycle(keyspace, new SplittableRandom(42), budget);
    }

    private void putKeys(String prefix, int count, long expireAtMillis) {
        for (int i = 0; i < count; i++) {
            keyspace.put(Key.of(prefix + ":" + i), new Entry("v".getBytes(StandardCharsets.UTF_8), expireAtMillis));
        }
    }
}
