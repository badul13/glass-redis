package glassredis.store;

import glassredis.observe.Event;
import glassredis.observe.Event.RemovalReason;
import glassredis.observe.EventBus;

import java.util.Objects;

/**
 * 주기적 만료. 아무도 읽지 않아 "읽을 때 확인" 경로로는 영영 지워지지 않을 키를 치운다.
 * Redis 7.2 {@code expire.c} 의 {@code activeExpireCycle} 을 옮겼다(기본 설정 active-expire-effort 1 기준).
 *
 * <h2>한 바퀴에 하는 일</h2>
 * <ol>
 *   <li>expires(만료 시각이 걸린 키만 든 해시 테이블)를 <b>커서로 이어서</b> 훑는다. 지난번에 멈춘 버킷부터다.
 *       키 20개를 보거나 버킷 400개(20 × 20)를 넘길 때까지 훑는다. 빈 버킷만 계속 나와도 끝은 있다.</li>
 *   <li>본 키 중 만료된 것을 지운다.</li>
 *   <li>본 키의 10% 넘게 만료돼 있었으면 1번을 다시 한다. 아직 치울 게 많다는 뜻이다.</li>
 * </ol>
 * 무작위로 뽑지 않고 커서로 버킷을 이어서 훑는 게 요점이다. 이렇게 하면
 * 한 바퀴를 도는 동안 모든 키를 한 번은 반드시 보게 되고, 같은 키를 헛되이 여러 번 뽑는 일도 없다.
 *
 * <h2>SLOW 와 FAST 두 가지</h2>
 * <ul>
 *   <li><b>SLOW</b> — 서버 주기 작업(1초에 10번)이 부른다. 한 번에 최대 25ms(주기 100ms 의 25%)까지 쓴다.
 *       주된 청소는 이쪽이 한다.</li>
 *   <li><b>FAST</b> — 실제 Redis 는 이벤트 루프가 잠들기 직전마다 부른다. glass-redis 에는 이벤트 루프가 없어서
 *       "실행 큐가 비어 쉬기 직전"에 부른다. 최대 1ms 만 쓰고, 직전 SLOW 가 시간 초과로 끝났거나 만료됐는데 남아 있는
 *       키의 비율 추정치가 10% 를 넘을 때만 돈다. 2ms 안에 다시 돌지도 않는다. SLOW 가 다 못 치운 걸 틈틈이 줍는 역할이다.</li>
 * </ul>
 * 시간 제한이 있는 이유: 이 코드는 명령을 실행하는 바로 그 스레드에서 돈다. 키 수십만 개가 한꺼번에 만료돼도
 * 한 번에 다 치우면 그동안 모든 클라이언트가 멈춘다. 그래서 정해진 시간을 넘기면 남은 일은 다음 차례로 미룬다.
 * 시간은 16 바퀴마다 한 번씩만 잰다 — 시계를 읽는 것도 비용이다.
 */
public final class ActiveExpireCycle {

    /** 어느 쪽 주기인지. */
    public enum Kind { SLOW, FAST }

    /** 한 바퀴에 볼 키 수(ACTIVE_EXPIRE_CYCLE_KEYS_PER_LOOP). */
    static final int KEYS_PER_LOOP = 20;
    /** FAST 한 번의 시간 한도, µs(ACTIVE_EXPIRE_CYCLE_FAST_DURATION). */
    static final long FAST_DURATION_MICROS = 1000;
    /** SLOW 가 주기 하나에서 쓸 수 있는 시간 비율, %(ACTIVE_EXPIRE_CYCLE_SLOW_TIME_PERC). */
    static final int SLOW_TIME_PERCENT = 25;
    /** 본 키 중 만료된 비율이 이걸 넘으면 한 바퀴 더(ACTIVE_EXPIRE_CYCLE_ACCEPTABLE_STALE). */
    static final int ACCEPTABLE_STALE_PERCENT = 10;
    /** 서버 주기 작업의 빈도. 실제 Redis 설정 {@code hz} 의 기본값. */
    public static final int HZ = 10;

    private final Keyspace keyspace;
    private final EventBus events;

    /** expires 를 어디까지 훑었는지. 다음 호출이 여기서 이어간다. */
    private long cursor;
    /** 직전 호출이 시간 한도에 걸려 끝났는지. 그랬다면 아직 치울 게 남았다는 신호다. */
    private boolean timeLimitExit;
    /** 마지막 FAST 가 시작된 시각(µs). */
    private long lastFastCycleMicros = Long.MIN_VALUE / 2;
    /** 만료됐는데 아직 남아 있는 키의 비율 추정치, 0~1. 매번 5% 가중치로 섞는 이동 평균이다. */
    private double stalePercentEstimate;

    public ActiveExpireCycle(Keyspace keyspace, EventBus events) {
        this.keyspace = Objects.requireNonNull(keyspace, "keyspace");
        this.events = Objects.requireNonNull(events, "events");
    }

    /** @return 이번에 지운 키의 수 */
    public int run(Kind kind) {
        long startMicros = System.nanoTime() / 1000;

        if (kind == Kind.FAST) {
            // 할 일이 별로 없어 보이면 아예 돌지 않는다.
            if (!timeLimitExit && stalePercentEstimate * 100 < ACCEPTABLE_STALE_PERCENT) {
                return 0;
            }
            // FAST 는 자기 시간 한도의 두 배 안에는 다시 돌지 않는다.
            if (startMicros < lastFastCycleMicros + FAST_DURATION_MICROS * 2) {
                return 0;
            }
            lastFastCycleMicros = startMicros;
        }

        long timeLimitMicros = kind == Kind.FAST
                ? FAST_DURATION_MICROS
                : Math.max(1, SLOW_TIME_PERCENT * 1_000_000L / HZ / 100);
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
            // 버킷의 1% 도 안 차 있으면 훑어봐야 빈 칸만 나온다. 테이블이 줄어들 때까지 기다린다.
            long slots = keyspace.expiresSlots();
            if (slots > 4 && num * 100 / slots < 1) {
                break;
            }

            long now = keyspace.now();
            long[] counts = new long[2]; // [본 수, 지운 수]
            num = Math.min(num, KEYS_PER_LOOP);
            long maxBuckets = num * 20;
            long checkedBuckets = 0;
            while (counts[0] < num && checkedBuckets < maxBuckets) {
                cursor = keyspace.scanExpires(cursor, (key, expireAt) -> {
                    if (now > expireAt && keyspace.expireIfDue(key, RemovalReason.ACTIVE_EXPIRED)) {
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

            if ((iteration & 0xf) == 0 && System.nanoTime() / 1000 - startMicros > timeLimitMicros) {
                timeLimitExit = true;
                break;
            }
        } while (sampled == 0 || expired * 100 / sampled > ACCEPTABLE_STALE_PERCENT);

        double current = totalSampled == 0 ? 0 : (double) totalExpired / totalSampled;
        stalePercentEstimate = current * 0.05 + stalePercentEstimate * 0.95;

        publish(kind, iteration, totalSampled, totalExpired, System.nanoTime() / 1000 - startMicros);
        return (int) totalExpired;
    }

    /** 지금 커서. 대시보드가 "만료 청소가 테이블 어디쯤을 훑고 있는지" 보여줄 때 쓴다. */
    public long cursor() {
        return cursor;
    }

    /** 만료됐는데 아직 남아 있는 키의 비율 추정치(0~1). */
    public double stalePercentEstimate() {
        return stalePercentEstimate;
    }

    private void publish(Kind kind, int iterations, long sampled, long expired, long micros) {
        // 볼 키가 하나도 없어서 아무 일도 하지 않은 주기는 알리지 않는다.
        // 100ms 마다 "할 일 없음"을 보내봐야 화면에 그릴 것은 없고 버퍼만 밀려난다.
        if (sampled > 0 && events.enabled()) {
            events.publish(new Event.ExpiryCycleCompleted(kind.name(), iterations, (int) sampled, (int) expired,
                    micros * 1000, timeLimitExit, Math.round(stalePercentEstimate * 1000) / 10.0));
        }
    }
}
