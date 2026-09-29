package glassredis.store;

import glassredis.observe.Event;
import glassredis.observe.Event.RemovalReason;
import glassredis.observe.EventBus;
import glassredis.store.encoding.Dict;

import java.time.InstantSource;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * 키스페이스 - redisDb처럼 dict(키 → 값)와 expires(키 → 만료 ms) 분리
 * expires에는 만료 걸린 키만 - 주기적 만료는 이것만 탐색
 * 락 없음 - 실행 스레드 전용
 */
public final class Keyspace {

    private final Dict<Value> dict = new Dict<>();
    /** 만료 걸린 키만 */
    private final Dict<Long> expires = new Dict<>();

    private final InstantSource clock;

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

    /** 에포크 ms - 명령의 만료 시각 계산도 이 시계 기준, 판단 불일치 방지 */
    public long now() {
        return clock.millis();
    }

    /** 없거나 만료 시 null, 만료 키는 이 시점 삭제 - lookupKey → expireIfNeeded */
    public Entry get(Key key) {
        Value value = dict.get(key);
        if (value == null || expireIfDue(key, RemovalReason.LAZY_EXPIRED)) {
            return null;
        }
        Long expireAt = expires.get(key);
        return new Entry(value, expireAt == null ? Entry.NO_EXPIRY : expireAt);
    }

    /** 만료 확인·rehash 없는 읽기 - 대시보드가 상태를 바꾸지 않도록 get 대신 사용 */
    public Entry peek(Key key) {
        Value value = dict.peek(key);
        if (value == null) {
            return null;
        }
        Long expireAt = expires.peek(key);
        return new Entry(value, expireAt == null ? Entry.NO_EXPIRY : expireAt);
    }

    /** 만료 없는 entry면 기존 만료도 제거 */
    public void put(Key key, Entry entry) {
        dict.put(key, entry.value());
        if (entry.hasExpiry()) {
            expires.put(key, entry.expireAtMillis());
        } else {
            expires.remove(key);
        }
    }

    /** 없거나 만료된 키면 false */
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

    /** 만료됐지만 미삭제 키 포함 */
    public int size() {
        return (int) dict.size();
    }

    /** 만료 시 삭제 후 true - 지연 만료, 주기적 만료 공통 경로 */
    public boolean expireIfDue(Key key, RemovalReason reason) {
        Long expireAt = expires.get(key);
        long now = now();
        // 같은 ms에는 생존 - now > when
        if (expireAt == null || now <= expireAt) {
            return false;
        }
        delete(key);
        if (events.enabled()) {
            // 세 번째 인자 - 만료 시각부터 실제 삭제까지 지연(ms)
            events.publish(Event.keyRemoved(key.bytes(), reason, now - expireAt));
        }
        return true;
    }

    /**
     * 순회 중 키스페이스 수정 금지, rehash 없음
     * 만료됐지만 미삭제 키도 의도적으로 포함
     */
    public void forEach(BiConsumer<Key, Entry> visitor) {
        dict.forEach((key, value) -> {
            Long expireAt = expires.peek(key);
            visitor.accept(key, new Entry(value, expireAt == null ? Entry.NO_EXPIRY : expireAt));
        });
    }

    public int expiringKeyCount() {
        return (int) expires.size();
    }

    // --- 주기적 만료·주기 작업용 ---

    /** dictSlots - 채움률 낮으면 주기적 만료 생략 */
    long expiresSlots() {
        return expires.slots();
    }

    /** 반환값 - 다음 커서 */
    long scanExpires(long cursor, BiConsumer<Key, Long> visitor) {
        return expires.scan(cursor, visitor);
    }

    /** 너무 빈 테이블 축소 - tryResizeHashTables */
    public void tryResizeHashTables() {
        if (dict.needsResize()) {
            dict.resize();
        }
        if (expires.needsResize()) {
            expires.resize();
        }
    }

    /**
     * rehash 중인 테이블 1ms 진행 - incrementallyRehash, 작업 시 true
     * 한 번에 한 테이블만
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

    /** 대시보드용 - 읽기 전용 */
    public Dict<Value> mainDict() {
        return dict;
    }

    /** 대시보드용 - 읽기 전용 */
    public Dict<Long> expiresDict() {
        return expires;
    }

    private void delete(Key key) {
        dict.remove(key);
        expires.remove(key);
    }
}
