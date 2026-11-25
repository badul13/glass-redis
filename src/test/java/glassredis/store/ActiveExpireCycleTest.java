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
    /** server.hz 기본값 */
    private static final int HZ = 10;

    /** 시간 한도 판정용 µs 시계 - 읽을 때마다 정해진 만큼 흐름 */
    private final long[] micros = {0};
    private long microsPerRead = 0;

    private final ActiveExpireCycle cycle = new ActiveExpireCycle(keyspace, EventBus.NONE, HZ, this::readMicros);

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

        assertTrue(expired < 20, "한 번에 전부 삭제: " + expired);
        keyspace.updateCommandTime();
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
    @DisplayName("FAST - 직전 주기가 시간 한도로 끝나지 않았으면 만료 비율 추정치와 무관하게 생략 (Redis 의 비율·퍼센트 비교 그대로)")
    void fastSkipsUnlessTimeLimitHitEvenWhenStaleRatioIsHigh() {
        // 전부 만료된 표본만 세 번 - 추정치 1 - 0.95^3 ≈ 0.14, 퍼센트로는 14% 로 10% 초과
        for (int round = 0; round < 3; round++) {
            putKeys("dead" + round, 100, clock.millis() + 10);
            clock.advanceMillis(20);
            assertEquals(100, cycle.run(Kind.SLOW));
        }
        putKeys("late", 50, clock.millis() + 10);
        clock.advanceMillis(20);

        // Redis expire.c - stat_expired_stale_perc(0~1) < 10 은 항상 참, timelimit_exit 만으로 결정
        assertEquals(0, cycle.run(Kind.FAST));
        assertEquals(50, keyspace.size());
    }

    @Test
    @DisplayName("키가 한꺼번에 많이 만료되면 SLOW 는 25ms 한도에서 남은 일 보류 - 시계 확인은 16바퀴마다")
    void slowRespectsTimeLimit() {
        EventHub hub = new EventHub();
        EventBuffer screen = hub.subscribe();
        Keyspace observed = new Keyspace(clock, hub);
        ActiveExpireCycle observedCycle = new ActiveExpireCycle(observed, hub, HZ, this::readMicros);
        // 한 바퀴 20개, 16바퀴째 첫 시계 확인 - 320개 넘게 있어야 한도 판정까지 감
        for (int i = 0; i < 1000; i++) {
            observed.put(Key.of("dead:" + i), Entry.of(bytes("v")).withExpireAt(clock.millis() + 10));
        }
        clock.advanceMillis(20);
        // 시작 시각 읽은 뒤 다음 읽기는 26ms 뒤 - 25ms(25% / hz 10) 초과
        microsPerRead = 26_000;

        observedCycle.run(Kind.SLOW);

        Event.ExpiryCycleCompleted summary = lastCycle(screen);
        assertTrue(summary.timeLimitHit(), "시간 한도 미도달");
        assertEquals(16, summary.rounds(), "16바퀴째에 처음 시계 확인");
        // 한 바퀴 최소 20개 - 버킷 단위 순회라 조금 더 볼 수 있음
        assertTrue(observed.size() > 0 && observed.size() <= 1000 - 16 * 20, "남은 키 " + observed.size());

        // 시간 한도로 끝났으니 다음 FAST 는 실행
        microsPerRead = 0;
        micros[0] += 1_000_000;
        assertTrue(observedCycle.run(Kind.FAST) > 0, "FAST 생략");
    }

    @Test
    @DisplayName("주기적 만료로 지운 키는 읽기 중 삭제와 다른 이유로 통지, 주기 요약도 통지")
    void publishesRemovalsAndSummary() {
        EventHub hub = new EventHub();
        EventBuffer screen = hub.subscribe();
        Keyspace observed = new Keyspace(clock, hub);
        observed.put(Key.of("k"), Entry.of(bytes("v")).withExpireAt(clock.millis() + 10));
        clock.advanceMillis(50);

        new ActiveExpireCycle(observed, hub, HZ).run(Kind.SLOW);

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

        new ActiveExpireCycle(new Keyspace(clock, hub), hub, HZ).run(Kind.SLOW);

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

    private long readMicros() {
        long now = micros[0];
        micros[0] += microsPerRead;
        return now;
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
