package glassredis.store;

import glassredis.observe.Event;
import glassredis.observe.Event.RemovalReason;
import glassredis.observe.EventBus;
import glassredis.store.encoding.Dict;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * 주기적 만료 - Redis 7.2 expire.c activeExpireCycle (active-expire-effort 1)
 * expires에서 키 20개 확인 후 10% 초과 만료 시 재탐색 - 커서로 이어서 진행
 * 실행 스레드에서 진행 - slow는 25ms(hz 10 기준), fast는 1ms를 넘기면 보류
 */
public final class ActiveExpireCycle {

    /** 호출 시점 - SLOW는 주기 작업, FAST는 실행 큐가 빈 유휴 직전 */
    public enum Kind { SLOW, FAST }

    /** ACTIVE_EXPIRE_CYCLE_KEYS_PER_LOOP */
    static final int KEYS_PER_LOOP = 20;
    /** ACTIVE_EXPIRE_CYCLE_FAST_DURATION, µs */
    static final long FAST_DURATION_MICROS = 1000;
    /** ACTIVE_EXPIRE_CYCLE_SLOW_TIME_PERC */
    static final int SLOW_TIME_PERCENT = 25;
    /** ACTIVE_EXPIRE_CYCLE_ACCEPTABLE_STALE - 퍼센트 */
    static final int ACCEPTABLE_STALE_PERCENT = 10;

    private final Keyspace keyspace;
    private final EventBus events;
    /** server.hz - SLOW 시간 한도 계산 */
    private final int hz;
    /** ustime() - 시간 한도 측정용 µs 시계 */
    private final LongSupplier micros;

    /** db->expires_cursor */
    private long cursor;
    /** timelimit_exit - 직전 호출의 시간 한도 종료 여부 */
    private boolean timeLimitExit;
    /** last_fast_cycle */
    private long lastFastCycleMicros = Long.MIN_VALUE / 2;
    /** stat_expired_stale_perc - 이름과 달리 0~1 비율, 5% 가중 이동 평균 */
    private double staleRatio;

    public ActiveExpireCycle(Keyspace keyspace, EventBus events, int hz) {
        this(keyspace, events, hz, () -> System.nanoTime() / 1000);
    }

    /** @param micros 시간 한도 측정용 µs 시계 - 테스트는 결정적 시계 */
    ActiveExpireCycle(Keyspace keyspace, EventBus events, int hz, LongSupplier micros) {
        this.keyspace = Objects.requireNonNull(keyspace, "keyspace");
        this.events = Objects.requireNonNull(events, "events");
        this.hz = hz;
        this.micros = Objects.requireNonNull(micros, "micros");
    }

    /** 반환값 - 삭제한 키 수 */
    public int run(Kind kind) {
        long startMicros = micros.getAsLong();

        if (kind == Kind.FAST) {
            // Redis 버그 그대로 재현 - 0~1 비율을 퍼센트(10)와 비교해 항상 참
            // 결국 직전 주기가 시간 한도로 끝났을 때만 FAST 실행 (expire.c 의 stat_expired_stale_perc 비교)
            if (!timeLimitExit && staleRatio < ACCEPTABLE_STALE_PERCENT) {
                return 0;
            }
            // 직전 FAST 시작 후 한도 두 배 시간 안에는 재실행 없음
            if (startMicros < lastFastCycleMicros + FAST_DURATION_MICROS * 2) {
                return 0;
            }
            lastFastCycleMicros = startMicros;
        }

        long timeLimitMicros = kind == Kind.FAST
                ? FAST_DURATION_MICROS
                : Math.max(1, SLOW_TIME_PERCENT * 1_000_000L / hz / 100);
        timeLimitExit = false;

        long totalSampled = 0;
        long totalExpired = 0;
        int iteration = 0;
        long sampled;
        long expired;
        do {
            iteration++;
            long num = keyspace.expiringKeyCount();
            if (num == 0) {
                break;
            }
            long slots = keyspace.expiresSlots();
            // data.now = mstime() - 명령 시각 스냅샷이 아닌 지금 시계
            long now = keyspace.clockMillis();
            // 채움률 1% 미만이면 빈 버킷뿐 - 테이블 축소 전까지 생략
            if (slots > Dict.INITIAL_SIZE && num * 100 / slots < 1) {
                break;
            }

            long[] counts = new long[2]; // [확인 수, 삭제 수]
            num = Math.min(num, KEYS_PER_LOOP);
            long maxBuckets = num * 20;
            long checkedBuckets = 0;
            while (counts[0] < num && checkedBuckets < maxBuckets) {
                cursor = keyspace.scanExpires(cursor, (key, expireAt) -> {
                    // activeExpireCycleTryExpire - enterExecutionUnit 으로 스냅샷 갱신 후 삭제·전파
                    if (now > expireAt) {
                        keyspace.updateCommandTime();
                        keyspace.deleteExpired(key, expireAt, RemovalReason.ACTIVE_EXPIRED);
                        counts[1]++;
                    }
                    counts[0]++;
                });
                checkedBuckets++;
            }
            sampled = counts[0];
            expired = counts[1];
            totalSampled += sampled;
            totalExpired += expired;

            // 시계 읽기 비용 때문에 16바퀴마다 측정
            if ((iteration & 0xf) == 0 && micros.getAsLong() - startMicros > timeLimitMicros) {
                timeLimitExit = true;
                break;
            }
        } while (sampled == 0 || expired * 100 / sampled > ACCEPTABLE_STALE_PERCENT);

        double current = totalSampled == 0 ? 0 : (double) totalExpired / totalSampled;
        staleRatio = current * 0.05 + staleRatio * 0.95;

        publish(kind, iteration, totalSampled, totalExpired, micros.getAsLong() - startMicros);
        return (int) totalExpired;
    }

    private void publish(Kind kind, int iterations, long sampled, long expired, long elapsedMicros) {
        // 빈 주기는 이벤트 버퍼만 밀어내므로 알림 생략
        if (sampled > 0 && events.enabled()) {
            events.publish(new Event.ExpiryCycleCompleted(kind.name(), iterations, (int) sampled, (int) expired,
                    elapsedMicros * 1000, timeLimitExit, Math.round(staleRatio * 1000) / 10.0));
        }
    }
}
