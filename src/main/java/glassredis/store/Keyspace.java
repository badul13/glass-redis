package glassredis.store;

import glassredis.observe.Event;
import glassredis.observe.Event.RemovalReason;
import glassredis.observe.EventBus;

import java.time.InstantSource;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.random.RandomGenerator;

/**
 * 데이터 본체. 키 → 값.
 *
 * <p>평범한 {@code HashMap} 이고 락이 하나도 없다. 이 객체는 실행 스레드({@code CommandLoop}) 하나만 만지기 때문이다.
 * 커넥션 스레드가 여기에 직접 손대는 순간 이 전제가 깨지므로, 명령을 거치지 않는 접근 경로를 만들지 않는다.
 *
 * <p>만료된 키는 두 경로로 지워진다.
 * <ul>
 *   <li><b>읽을 때 확인</b> — {@link #get} 이 만료 시각을 보고, 지났으면 그 자리에서 지우고 없는 키로 답한다.
 *       그래서 명령 입장에서는 만료된 키가 절대 보이지 않는다.</li>
 *   <li><b>주기적 샘플링</b> — 아무도 다시 읽지 않는 키는 위 경로로는 영원히 지워지지 않는다.
 *       {@link ExpiryCycle} 이 만료 시각이 있는 키를 무작위로 골라 지운다.</li>
 * </ul>
 * 두 경로 모두 {@link #expireIfDue} 하나를 거친다. 어느 쪽으로 지워졌는지는 이벤트에 담아 알린다 —
 * 그 차이를 눈으로 보여주는 것이 이 프로젝트의 목적이다.
 */
public final class Keyspace {

    private final Map<Key, Entry> entries = new HashMap<>();

    /** 만료 시각이 있는 키만 따로 모아둔 것. 주기적 샘플링이 여기서 무작위로 고른다. */
    private final ExpiringKeys expiringKeys = new ExpiringKeys();

    /** 만료 판단에 쓰는 시계. 테스트에서는 손으로 돌리는 시계를 넣어 {@code sleep} 없이 시간을 건너뛴다. */
    private final InstantSource clock;

    /** 키가 사라질 때 알릴 곳. 관측이 꺼져 있으면 {@link EventBus#NONE} 이라 아무 비용도 들지 않는다. */
    private final EventBus events;

    public Keyspace() {
        this(InstantSource.system(), EventBus.NONE);
    }

    public Keyspace(InstantSource clock) {
        this(clock, EventBus.NONE);
    }

    public Keyspace(EventBus events) {
        this(InstantSource.system(), events);
    }

    public Keyspace(InstantSource clock, EventBus events) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.events = Objects.requireNonNull(events, "events");
    }

    /** 현재 시각(에포크 ms). 명령이 만료 시각을 계산할 때도 이 시계를 써야 판단이 어긋나지 않는다. */
    public long now() {
        return clock.millis();
    }

    /** 없거나 이미 만료됐으면 {@code null}. 만료된 키는 이때 지워진다. */
    public Entry get(Key key) {
        return expireIfDue(key, RemovalReason.LAZY_EXPIRED) ? null : entries.get(key);
    }

    public void put(Key key, Entry entry) {
        entries.put(key, entry);
        if (entry.hasExpiry()) {
            expiringKeys.add(key);
        } else {
            // 만료 없는 값으로 덮어쓴 키가 샘플링 대상에 남아 있으면 안 된다.
            expiringKeys.remove(key);
        }
    }

    /** 실제로 지웠으면 {@code true}. 원래 없었거나 이미 만료된 키면 {@code false}. */
    public boolean remove(Key key) {
        if (get(key) == null) {
            return false;
        }
        delete(key);
        if (events.enabled()) {
            events.publish(Event.keyRemoved(key.bytes(), RemovalReason.DELETED, 0));
        }
        return true;
    }

    /** 만료됐지만 아직 지워지지 않은 키도 센다. */
    public int size() {
        return entries.size();
    }

    /**
     * 만료됐으면 지우고 {@code true}. 없거나 아직 살아 있으면 {@code false}.
     *
     * @param reason 지워졌을 때 이벤트에 담을 이유. 읽다가 걸린 것인지 샘플링에 걸린 것인지를 구분한다.
     */
    public boolean expireIfDue(Key key, RemovalReason reason) {
        Entry entry = entries.get(key);
        long now = now();
        if (entry != null && entry.isExpiredAt(now)) {
            delete(key);
            if (events.enabled()) {
                // 만료 시각이 지나고 실제로 지워지기까지 걸린 시간. 이 값이 0 이 아니라는 것이
                // "SET k v EX 10 을 해도 정확히 10초에 지워지지는 않는다"는 말의 증거다.
                events.publish(Event.keyRemoved(key.bytes(), reason, now - entry.expireAtMillis()));
            }
            return true;
        }
        return false;
    }

    /**
     * 담긴 키를 하나씩 넘겨준다. 대시보드가 키 목록을 뜰 때 쓴다.
     *
     * <p>다른 모든 접근과 마찬가지로 실행 스레드에서만 불러야 한다. 넘겨주는 동안 키스페이스를
     * 고치면 안 된다 — 읽기 전용으로만 쓴다.
     *
     * <p>만료 시각이 지났지만 아직 지워지지 않은 키도 그대로 넘어온다. {@link #get} 과 다른 점이고,
     * 의도한 것이다. 그 키가 아직 메모리에 남아 있다는 사실 자체를 화면에 보여줘야 한다.
     */
    public void forEach(BiConsumer<Key, Entry> visitor) {
        entries.forEach(visitor);
    }

    /** 만료 시각이 있는 키의 수. */
    public int expiringKeyCount() {
        return expiringKeys.size();
    }

    /** 만료 시각이 있는 키 중 하나를 무작위로 고른다. 그런 키가 하나도 없을 때 부르면 안 된다. */
    public Key randomExpiringKey(RandomGenerator random) {
        return expiringKeys.random(random);
    }

    private void delete(Key key) {
        entries.remove(key);
        expiringKeys.remove(key);
    }
}
