package glassredis.store;

import glassredis.observe.Event;
import glassredis.observe.Event.RemovalReason;
import glassredis.observe.EventBus;
import glassredis.store.encoding.Dict;

import java.time.InstantSource;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * 데이터 본체. 실제 Redis 의 {@code redisDb} 처럼 해시 테이블({@link Dict}) 두 개로 이뤄진다.
 * <pre>
 *   dict     키 → 값
 *   expires  키 → 만료 시각(에포크 ms)     만료 시각이 걸린 키만 들어 있다
 * </pre>
 * 만료 시각을 값 옆에 붙이지 않고 따로 두는 이유는 주기적 만료 때문이다. 만료를 치우러 돌 때 만료 시각이 걸린 키만
 * 훑으면 되는데, 한 테이블에 섞여 있으면 만료와 상관없는 키까지 다 넘겨봐야 한다.
 *
 * <p>락이 하나도 없다. 이 객체는 실행 스레드({@code CommandLoop}) 하나만 만지기 때문이다.
 * 커넥션 스레드가 여기에 직접 손대는 순간 이 전제가 깨지므로, 명령을 거치지 않는 접근 경로를 만들지 않는다.
 *
 * <p>만료된 키는 두 경로로 지워진다.
 * <ul>
 *   <li><b>읽을 때 확인</b> — {@link #get} 이 만료 시각을 보고, 지났으면 그 자리에서 지우고 없는 키로 답한다.
 *       그래서 명령 입장에서는 만료된 키가 절대 보이지 않는다.</li>
 *   <li><b>주기적 만료</b> — 아무도 다시 읽지 않는 키는 위 경로로는 영원히 지워지지 않는다.
 *       {@link ActiveExpireCycle} 이 expires 를 커서로 조금씩 훑으며 지운다.</li>
 * </ul>
 * 두 경로 모두 {@link #expireIfDue} 하나를 거친다. 어느 쪽으로 지워졌는지는 이벤트에 담아 알린다 —
 * 그 차이를 눈으로 보여주는 것이 이 프로젝트의 목적이다.
 */
public final class Keyspace {

    private final Dict<Value> dict = new Dict<>();
    private final Dict<Long> expires = new Dict<>();

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

    /** 없거나 이미 만료됐으면 {@code null}. 만료된 키는 이때 지워진다(lookupKey → expireIfNeeded). */
    public Entry get(Key key) {
        Value value = dict.get(key);
        if (value == null || expireIfDue(key, RemovalReason.LAZY_EXPIRED)) {
            return null;
        }
        Long expireAt = expires.get(key);
        return new Entry(value, expireAt == null ? Entry.NO_EXPIRY : expireAt);
    }

    /**
     * 만료를 확인하지 않고, 테이블 옮기기도 한 칸 하지 않고 들여다본다. 대시보드가 키 하나를 자세히 볼 때 쓴다.
     *
     * <p>{@link #get} 을 쓰면 안 된다. 만료된 키를 들여다보는 순간 그 자리에서 지워버리므로,
     * 화면이 보고만 있어도 서버 상태가 바뀐다. 관측이 관측 대상을 건드리면 안 된다.
     */
    public Entry peek(Key key) {
        Value value = dict.peek(key);
        if (value == null) {
            return null;
        }
        Long expireAt = expires.peek(key);
        return new Entry(value, expireAt == null ? Entry.NO_EXPIRY : expireAt);
    }

    /** 값을 넣고, 만료 시각을 entry 에 적힌 대로 맞춘다. 만료 시각이 없는 entry 면 기존 만료도 지운다. */
    public void put(Key key, Entry entry) {
        dict.put(key, entry.value());
        if (entry.hasExpiry()) {
            expires.put(key, entry.expireAtMillis());
        } else {
            expires.remove(key);
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
        return (int) dict.size();
    }

    /**
     * 만료됐으면 지우고 {@code true}. 없거나 아직 살아 있으면 {@code false}.
     *
     * @param reason 지워졌을 때 이벤트에 담을 이유. 읽다가 걸린 것인지 주기적 만료에 걸린 것인지를 구분한다.
     */
    public boolean expireIfDue(Key key, RemovalReason reason) {
        Long expireAt = expires.get(key);
        long now = now();
        // 만료 시각과 정확히 같은 ms 에는 아직 살아 있다. 실제 Redis 도 now > when 으로 판단한다.
        if (expireAt == null || now <= expireAt) {
            return false;
        }
        delete(key);
        if (events.enabled()) {
            // 만료 시각이 지나고 실제로 지워지기까지 걸린 시간. 이 값이 0 이 아니라는 것이
            // "SET k v EX 10 을 해도 정확히 10초에 지워지지는 않는다"는 말의 증거다.
            events.publish(Event.keyRemoved(key.bytes(), reason, now - expireAt));
        }
        return true;
    }

    /**
     * 담긴 키를 하나씩 넘겨준다. 대시보드가 키 목록을 뜰 때 쓴다.
     *
     * <p>다른 모든 접근과 마찬가지로 실행 스레드에서만 불러야 한다. 넘겨주는 동안 키스페이스를
     * 고치면 안 된다 — 읽기 전용으로만 쓴다. 테이블 옮기기도 하지 않는다.
     *
     * <p>만료 시각이 지났지만 아직 지워지지 않은 키도 그대로 넘어온다. {@link #get} 과 다른 점이고,
     * 의도한 것이다. 그 키가 아직 메모리에 남아 있다는 사실 자체를 화면에 보여줘야 한다.
     */
    public void forEach(BiConsumer<Key, Entry> visitor) {
        dict.forEach((key, value) -> {
            Long expireAt = expires.peek(key);
            visitor.accept(key, new Entry(value, expireAt == null ? Entry.NO_EXPIRY : expireAt));
        });
    }

    /** 만료 시각이 있는 키의 수. */
    public int expiringKeyCount() {
        return (int) expires.size();
    }

    // --- 주기적 만료와 주기 작업이 쓰는 것 ---

    /** expires 의 버킷 수(dictSlots). 채움률이 너무 낮으면 주기적 만료가 훑기를 건너뛴다. */
    long expiresSlots() {
        return expires.slots();
    }

    /** expires 를 커서 자리에서 한 칸 훑는다. 다음 커서를 돌려준다. visitor 는 (키, 만료 시각) 을 받는다. */
    long scanExpires(long cursor, BiConsumer<Key, Long> visitor) {
        return expires.scan(cursor, visitor);
    }

    /** 너무 비어 버린 테이블을 줄인다(tryResizeHashTables). 서버의 주기 작업이 부른다. */
    public void tryResizeHashTables() {
        if (dict.needsResize()) {
            dict.resize();
        }
        if (expires.needsResize()) {
            expires.resize();
        }
    }

    /**
     * 옮기는 중인 테이블이 있으면 1ms 동안 밀어준다(incrementallyRehash). 명령이 뜸할 때도 옮기기가 끝나게 한다.
     * 일을 했으면 {@code true}. dict 를 먼저 보고, 거기서 일했으면 expires 는 다음 차례로 미룬다.
     */
    public boolean incrementallyRehash() {
        if (dict.isRehashing()) {
            dict.rehashMilliseconds(1);
            return true;
        }
        if (expires.isRehashing()) {
            expires.rehashMilliseconds(1);
            return true;
        }
        return false;
    }

    /** 대시보드용: 키 → 값 테이블. 읽기만 한다. */
    public Dict<Value> mainDict() {
        return dict;
    }

    /** 대시보드용: 키 → 만료 시각 테이블. 읽기만 한다. */
    public Dict<Long> expiresDict() {
        return expires;
    }

    private void delete(Key key) {
        dict.remove(key);
        expires.remove(key);
    }
}
