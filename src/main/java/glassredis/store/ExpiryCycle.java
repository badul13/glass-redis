package glassredis.store;

import glassredis.observe.Event;
import glassredis.observe.Event.RemovalReason;
import glassredis.observe.EventBus;

import java.time.Duration;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * 주기적 만료 샘플링 한 번.
 *
 * <p>Redis 문서(EXPIRE 명령 페이지의 "How Redis expires keys")에 설명된 방식을 따른다.
 * <ol>
 *   <li>만료 시각이 있는 키 중 20개를 무작위로 골라</li>
 *   <li>만료된 것을 지우고</li>
 *   <li>그중 25% 넘게 만료돼 있었으면 1번부터 다시 한다.</li>
 * </ol>
 *
 * <p>표본에서 만료된 비율은 전체에서 만료된 비율의 추정치다. 표본의 25% 넘게 만료돼 있었다면 아직 치울 게 많다는
 * 뜻이니 한 번 더 돌고, 그 아래면 멈춘다. 모든 키를 훑지 않고도 "만료됐는데 메모리를 차지하고 있는 키"의 비율을
 * 대략 25% 아래로 묶어둘 수 있다. 대신 정확히 만료 시각에 지운다는 보장은 없다 — 그건 읽을 때 확인하는 쪽의 몫이다.
 *
 * <p>여기에 시간 제한을 하나 더 둔다. 키 수십만 개가 한꺼번에 만료되면 25% 규칙만으로는 한참 반복하는데,
 * 이 코드는 실행 스레드에서 돌기 때문에 그동안 모든 클라이언트의 명령이 멈춘다.
 * 그래서 제한 시간을 넘기면 남은 일은 다음 주기로 미룬다.
 *
 * <p>표본은 복원 추출이다(같은 키가 두 번 뽑힐 수 있다). 만료된 키는 뽑히는 즉시 지워지므로
 * 같은 키를 두 번 지우는 일은 없고, 살아 있는 키가 두 번 검사될 수 있을 뿐이다.
 */
public final class ExpiryCycle {

    /** 한 번에 검사하는 키 수. */
    public static final int SAMPLE_SIZE = 20;

    /** 표본에서 이 비율보다 많이 만료돼 있으면 한 번 더 돈다. */
    private static final int REPEAT_THRESHOLD_PERCENT = 25;

    /** 샘플링 주기(100ms)의 1/4. 한 주기 안에서 샘플링이 실행 스레드를 독차지하는 시간을 이만큼으로 묶는다. */
    public static final Duration DEFAULT_TIME_BUDGET = Duration.ofMillis(25);

    private final Keyspace keyspace;
    private final RandomGenerator random;
    private final long timeBudgetNanos;
    private final EventBus events;

    public ExpiryCycle(Keyspace keyspace, RandomGenerator random, Duration timeBudget) {
        this(keyspace, random, timeBudget, EventBus.NONE);
    }

    public ExpiryCycle(Keyspace keyspace, RandomGenerator random, Duration timeBudget, EventBus events) {
        this.keyspace = Objects.requireNonNull(keyspace, "keyspace");
        this.random = Objects.requireNonNull(random, "random");
        this.timeBudgetNanos = timeBudget.toNanos();
        this.events = Objects.requireNonNull(events, "events");
    }

    /** @return 이번에 지운 키의 수 */
    public int run() {
        // 제한 시간은 키스페이스의 시계가 아니라 단조 시계로 잰다. 벽시계는 NTP 보정 등으로 뒤로 갈 수도 있다.
        long start = System.nanoTime();
        long deadline = start + timeBudgetNanos;
        int rounds = 0;
        int totalSampled = 0;
        int totalExpired = 0;

        while (true) {
            int sampled = 0;
            int expired = 0;
            while (sampled < SAMPLE_SIZE && keyspace.expiringKeyCount() > 0) {
                if (keyspace.expireIfDue(keyspace.randomExpiringKey(random), RemovalReason.ACTIVE_EXPIRED)) {
                    expired++;
                }
                sampled++;
            }
            rounds++;
            totalSampled += sampled;
            totalExpired += expired;

            boolean fewExpired = expired * 100 <= sampled * REPEAT_THRESHOLD_PERCENT;
            long now = System.nanoTime();
            if (sampled == 0 || fewExpired || now >= deadline) {
                publishCompletion(rounds, totalSampled, totalExpired, now - start);
                return totalExpired;
            }
        }
    }

    private void publishCompletion(int rounds, int sampled, int expired, long durationNanos) {
        // 뽑을 키가 하나도 없어서 아무 일도 하지 않은 주기는 알리지 않는다.
        // 100ms 마다 "할 일 없음"을 보내봐야 화면에 그릴 것은 없고 버퍼만 밀려난다.
        if (sampled > 0 && events.enabled()) {
            events.publish(new Event.ExpiryCycleCompleted(rounds, sampled, expired, durationNanos));
        }
    }
}
