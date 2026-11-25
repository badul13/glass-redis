package glassredis.store;

import glassredis.observe.Event;
import glassredis.observe.Event.RemovalReason;
import glassredis.observe.EventBus;
import glassredis.store.encoding.Dict;

import java.time.InstantSource;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 키스페이스 - redisDb처럼 dict(키 → 값)와 expires(키 → 만료 ms) 분리
 * expires에는 만료 걸린 키만 - 주기적 만료는 이것만 탐색
 * 조회·쓰기 함수는 db.c 의 함수 하나씩에 대응 - dict 를 건드리는 횟수(rehash 기회)까지 같게
 * 락 없음 - 실행 스레드 전용
 */
public final class Keyspace {

    /** setKey 플래그 - SETKEY_KEEPTTL: 기존 만료 유지 */
    public static final int SETKEY_KEEPTTL = 1;
    /** setKey 플래그 - SETKEY_ALREADY_EXIST: 호출 측이 이미 있음을 확인 */
    public static final int SETKEY_ALREADY_EXIST = 1 << 2;
    /** setKey 플래그 - SETKEY_DOESNT_EXIST: 호출 측이 없음을 확인 */
    public static final int SETKEY_DOESNT_EXIST = 1 << 3;
    /** setKey 플래그 - SETKEY_ADD_OR_UPDATE: 모르면 넣어 보고 있으면 갱신 */
    public static final int SETKEY_ADD_OR_UPDATE = 1 << 4;

    /** LRU_CLOCK_MAX - robj.lru 24비트 */
    static final int LRU_CLOCK_MAX = (1 << 24) - 1;
    /** LRU_CLOCK_RESOLUTION - ms */
    static final int LRU_CLOCK_RESOLUTION = 1000;

    /** lookupKey 플래그 */
    private static final int LOOKUP_NOTOUCH = 1;
    private static final int LOOKUP_WRITE = 1 << 3;

    /** expireIfNeeded 플래그 - EXPIRE_FORCE_DELETE_EXPIRED: 레플리카에서도 실제 삭제 */
    private static final int EXPIRE_FORCE_DELETE_EXPIRED = 1;

    /** dict 의 값 칸 - robj 의 값과 lru 필드 (공유 정수의 lru 는 sharedIntegerLru 쪽) */
    private static final class Slot {
        Value value;
        int lru;

        Slot(Value value) {
            this.value = value;
        }
    }

    /** emptyData 로 통째 교체 - 그 외엔 고정 */
    private Dict<Slot> dict = new Dict<>();
    /** 만료 걸린 키만 */
    private Dict<Long> expires = new Dict<>();

    /**
     * shared.integers[0..9999] 의 lru - 키끼리 공유
     * 처음엔 0 - createSharedObjects 의 initObjectLRUOrLFU 가 공유 객체는 건너뜀
     * 서버 전역 객체라 emptyData 로 지워지지 않음
     */
    private final int[] sharedIntegerLru = new int[StringValue.SHARED_INTEGERS];

    private final InstantSource clock;

    private final EventBus events;

    /** server.cmd_time_snapshot - 명령 시작 등에서만 갱신, 만료 판정·만료 시각 계산 기준 */
    private long commandTime;

    /** server.lruclock - 주기 작업이 갱신, hz 10 이면 LRU_CLOCK() 은 이 값 */
    private int lruClock;

    /** hasActiveChildProcess - 자식 작업 중이면 lru 갱신 생략 (copy-on-write 회피) */
    private BooleanSupplier childActive = () -> false;

    /** 만료 삭제 알림 - AOF에 DEL 전파 (propagateDeletion) */
    private Consumer<Key> expiredListener = key -> {
    };

    /** AOF 로딩 중 - 만료 판단 중지 (keyIsExpired, checkAlreadyExpired) */
    private boolean loading;

    /** 레플리카 - 만료 키를 지우지 않고 없는 것으로만 보임, 삭제는 마스터의 DEL 로 */
    private boolean replica;

    /** repl_slave_ro - 읽기 전용 레플리카면 쓰기 조회에서도 만료 키 삭제 안 함 */
    private boolean replicaReadOnly = true;

    /** 마스터 스트림 실행 중 - 레플리카는 이때 만료를 보지 않음 (CLIENT_MASTER) */
    private boolean masterStream;

    /** 직전 lookup 이 찾은 항목 - 값 제자리 교체(o->ptr 수정)를 재조회 없이 */
    private Key lookedUpKey;
    private Slot lookedUp;

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
        // initServerConfig - 시각 스냅샷과 LRU 시계 첫 값
        this.commandTime = clock.millis();
        updateLruClock();
    }

    // --- 시각 ---

    /**
     * 명령 시작 시각 스냅샷(ms) - commandTimeSnapshot
     * 만료 판정, EXPIRE·SET EX·TTL 의 기준 - 명령 하나 안에서는 시계가 흘러도 불변
     */
    public long now() {
        return commandTime;
    }

    /** 지금 시계(ms) - mstime(), 주기적 만료·RDB 로드의 지난 만료 판정 */
    public long clockMillis() {
        return clock.millis();
    }

    /** 스냅샷 갱신 - call() 의 enterExecutionUnit, afterSleep, 주기적 만료의 키 삭제 직전 */
    public void updateCommandTime() {
        commandTime = clock.millis();
    }

    /** server.lruclock = getLRUClock() - serverCron 마다 */
    public void updateLruClock() {
        lruClock = (int) ((clock.millis() / LRU_CLOCK_RESOLUTION) & LRU_CLOCK_MAX);
    }

    public void setChildActiveCheck(BooleanSupplier childActive) {
        this.childActive = Objects.requireNonNull(childActive, "childActive");
    }

    // --- 상태 ---

    public void setExpiredListener(Consumer<Key> listener) {
        this.expiredListener = Objects.requireNonNull(listener, "listener");
    }

    public void setLoading(boolean loading) {
        this.loading = loading;
    }

    /** 로딩 중이면 지난 만료 시각도 그대로 저장 - 삭제는 로딩 후 만료 처리가 DEL로 */
    public boolean loading() {
        return loading;
    }

    /** server.masterhost != NULL */
    public void setReplica(boolean replica) {
        this.replica = replica;
    }

    public boolean replica() {
        return replica;
    }

    /** replica-read-only 설정 */
    public void setReplicaReadOnly(boolean readOnly) {
        this.replicaReadOnly = readOnly;
    }

    /** current_client 가 마스터인 동안 */
    public void setMasterStream(boolean executing) {
        this.masterStream = executing;
    }

    /** mustObeyClient - 마스터 스트림 또는 AOF 로딩 중, 이미 받아들여진 명령이라 한도 검사 생략 */
    public boolean mustObeyClient() {
        return masterStream || loading;
    }

    /** emptyData - 키 전부 버림 */
    public void clear() {
        dict = new Dict<>();
        expires = new Dict<>();
        forgetLookedUp();
    }

    // --- 조회 (lookupKey 계열) ---

    /** lookupKeyRead - 없거나 만료면 null, 만료 키는 이 시점 삭제, 찾으면 lru 갱신 */
    public Entry get(Key key) {
        return lookup(key, 0);
    }

    /** lookupKeyWrite - 쓰기 가능한 레플리카에서도 만료 키를 실제로 삭제 (EXPIRE_FORCE_DELETE_EXPIRED) */
    public Entry getWrite(Key key) {
        return lookup(key, LOOKUP_WRITE);
    }

    /** lookupKeyReadWithFlags(LOOKUP_NOTOUCH) - lru 그대로 (TTL, TYPE, EXISTS, OBJECT) */
    public Entry getNoTouch(Key key) {
        return lookup(key, LOOKUP_NOTOUCH);
    }

    /** 만료 확인·rehash·lru 갱신 없는 읽기 - 대시보드·RDB 로더 중복 확인용 */
    public Entry peek(Key key) {
        Slot slot = dict.peek(key);
        if (slot == null) {
            return null;
        }
        Long expireAt = expires.peek(key);
        return new Entry(slot.value, expireAt == null ? Entry.NO_EXPIRY : expireAt);
    }

    /**
     * lookupKey - dictFind(dict) 한 번, 찾으면 expireIfNeeded (keyIsExpired 의 getExpire 한 번)
     * 로딩 중엔 keyIsExpired 가 getExpire 전에 끝나므로 만료 시각은 rehash 없는 peek 으로
     */
    private Entry lookup(Key key, int flags) {
        Slot slot = dict.get(key);
        if (slot == null) {
            return null;
        }
        long when = loading ? peekExpire(key) : getExpire(key);
        boolean roReplica = replica && replicaReadOnly;
        int expireFlags = (flags & LOOKUP_WRITE) != 0 && !roReplica ? EXPIRE_FORCE_DELETE_EXPIRED : 0;
        if (expireIfNeeded(key, when, expireFlags, RemovalReason.LAZY_EXPIRED)) {
            return null;
        }
        // 자식 작업 중엔 lru 갱신 생략 - copy-on-write 회피
        if (!childActive.getAsBoolean() && (flags & LOOKUP_NOTOUCH) == 0) {
            setLru(slot, lruClock);
        }
        lookedUpKey = key;
        lookedUp = slot;
        return new Entry(slot.value, when < 0 ? Entry.NO_EXPIRY : when);
    }

    /** getExpire - 만료 없으면 -1, expires 가 비었으면 dict 를 건드리지 않음 */
    public long getExpire(Key key) {
        if (expires.size() == 0) {
            return -1;
        }
        Long when = expires.get(key);
        return when == null ? -1 : when;
    }

    private long peekExpire(Key key) {
        Long when = expires.peek(key);
        return when == null ? -1 : when;
    }

    /** keyIsExpired - 삭제 없음 (KEYS), 로딩 중이면 항상 false */
    public boolean isExpired(Key key) {
        if (loading) {
            return false;
        }
        long when = getExpire(key);
        return when >= 0 && commandTime > when;
    }

    /** expireIfNeeded(db, key, 0) - 만료면 삭제(레플리카는 삭제 없이) 후 true (DEL) */
    public boolean expireIfNeeded(Key key) {
        if (loading) {
            return false;
        }
        return expireIfNeeded(key, getExpire(key), 0, RemovalReason.LAZY_EXPIRED);
    }

    /**
     * expireIfNeeded - when 은 keyIsExpired 가 읽은 만료 시각
     * 레플리카: 마스터 스트림 실행 중이면 만료 아님, 강제 삭제 플래그 없으면 삭제 없이 true
     */
    private boolean expireIfNeeded(Key key, long when, int flags, RemovalReason reason) {
        // 같은 ms에는 생존 - now > when
        if (loading || when < 0 || commandTime <= when) {
            return false;
        }
        if (replica) {
            if (masterStream) {
                return false;
            }
            if ((flags & EXPIRE_FORCE_DELETE_EXPIRED) == 0) {
                return true;
            }
        }
        deleteExpired(key, when, reason);
        return true;
    }

    /**
     * deleteExpiredKeyAndPropagate - dbGenericDelete + DEL 전파
     * 주기적 만료가 직접 부름 (activeExpireCycleTryExpire), 삭제 지연은 스냅샷 기준
     */
    public void deleteExpired(Key key, long expireAt, RemovalReason reason) {
        if (!deleteEntry(key)) {
            return;
        }
        expiredListener.accept(key);
        if (events.enabled()) {
            // 세 번째 인자 - 만료 시각부터 실제 삭제까지 지연(ms)
            events.publish(Event.keyRemoved(key.bytes(), reason, commandTime - expireAt));
        }
    }

    // --- 쓰기 (db.c) ---

    /**
     * dbAdd - 없는 키에만, dictAddRaw 한 번
     * lru 는 지금 LRU 시계 (initObjectLRUOrLFU) - 공유 정수는 공유 lru 그대로
     *
     * @throws IllegalStateException 이미 있는 키 - Redis 는 serverAssert
     */
    public void add(Key key, Value value) {
        Slot slot = newSlot(value);
        if (!dict.add(key, slot)) {
            throw new IllegalStateException("dbAdd 대상 키 이미 존재: " + key);
        }
    }

    /**
     * dbReplaceValue (dbSetValue) - 있는 키의 값만 교체, dictFind 한 번
     * 만료는 그대로, lru 는 옛 값의 것 (val->lru = old->lru)
     *
     * @throws IllegalStateException 없는 키 - Redis 는 serverAssert
     */
    public void replaceValue(Key key, Value value) {
        Slot slot = dict.get(key);
        if (slot == null) {
            throw new IllegalStateException("dbSetValue 대상 키 없음: " + key);
        }
        setValueKeepingLru(slot, value);
    }

    /**
     * 직전 조회한 값의 제자리 수정 - INCR 의 o->ptr 교체, APPEND 의 sdscatlen 에 해당
     * dict 재조회 없음, 같은 robj 라 lru 도 그대로
     * 직전 조회가 다른 키면 replaceValue 로
     */
    public void replaceLookedUpValue(Key key, Value value) {
        if (lookedUp != null && key.equals(lookedUpKey)) {
            lookedUp.value = value;
            return;
        }
        replaceValue(key, value);
    }

    /**
     * setKey - flags 로 이미 아는 존재 여부를 넘기면 다시 조회하지 않음
     * - 모름(0): lookupKeyWrite 후 dbAdd 또는 dbSetValue
     * - SETKEY_ADD_OR_UPDATE: dictAddRaw 한 번으로 넣거나 기존 항목 갱신
     * - SETKEY_KEEPTTL 없으면 removeExpire
     */
    public void setKey(Key key, Value value, int flags) {
        int keyFound;
        if ((flags & SETKEY_ALREADY_EXIST) != 0) {
            keyFound = 1;
        } else if ((flags & SETKEY_ADD_OR_UPDATE) != 0) {
            keyFound = -1;
        } else if ((flags & SETKEY_DOESNT_EXIST) == 0) {
            keyFound = getWrite(key) != null ? 1 : 0;
        } else {
            keyFound = 0;
        }

        if (keyFound == 0) {
            add(key, value);
        } else if (keyFound < 0) {
            addOrUpdate(key, value);
        } else {
            replaceValue(key, value);
        }
        if ((flags & SETKEY_KEEPTTL) == 0) {
            removeExpire(key);
        }
    }

    /**
     * setExpire - dictFind(dict) 한 번 + dictAddOrFind(expires) 한 번
     *
     * @throws IllegalStateException 없는 키 - Redis 는 serverAssert
     */
    public void setExpire(Key key, long when) {
        if (dict.find(key) == null) {
            throw new IllegalStateException("setExpire 대상 키 없음: " + key);
        }
        expires.put(key, when);
    }

    /** removeExpire - 지웠으면 true, expires 가 비었으면 dict 를 건드리지 않음 */
    public boolean removeExpire(Key key) {
        return expires.remove(key);
    }

    /**
     * 값과 만료를 한 번에 - dbAddRDBLoad + setExpire 모양 (RDB·테스트용)
     * 이미 있으면 값 교체 후 만료 없는 entry 면 기존 만료도 제거
     */
    public void put(Key key, Entry entry) {
        boolean existed = addOrUpdate(key, entry.value());
        if (entry.hasExpiry()) {
            setExpire(key, entry.expireAtMillis());
        } else if (existed) {
            removeExpire(key);
        }
    }

    /** dbSyncDelete - 만료 확인 없이 삭제, 지웠으면 true */
    public boolean delete(Key key) {
        if (!deleteEntry(key)) {
            return false;
        }
        if (events.enabled()) {
            events.publish(Event.keyRemoved(key.bytes(), RemovalReason.DELETED, 0));
        }
        return true;
    }

    /** 만료됐지만 미삭제 키 포함 */
    public int size() {
        return (int) dict.size();
    }

    /**
     * 순회 중 키스페이스 수정 금지, rehash 없음
     * 만료됐지만 미삭제 키도 의도적으로 포함
     */
    public void forEach(BiConsumer<Key, Entry> visitor) {
        dict.forEach((key, slot) -> {
            Long expireAt = expires.peek(key);
            visitor.accept(key, new Entry(slot.value, expireAt == null ? Entry.NO_EXPIRY : expireAt));
        });
    }

    public int expiringKeyCount() {
        return (int) expires.size();
    }

    /**
     * RDB RESIZEDB - 넣을 키 수만큼 미리 확장, 로드 중 rehash 방지
     * Redis 는 dictExpand 할당 실패 시 프로세스 중단, 여기선 예외로 넘겨 로더가 손상 처리
     *
     * @throws Dict.ExpandFailedException 확장할 메모리(배열 한도) 부족
     */
    public void expandForLoading(long keys, long expiringKeys) {
        if (!dict.tryExpand(keys)) {
            throw new Dict.ExpandFailedException(keys);
        }
        if (!expires.tryExpand(expiringKeys)) {
            throw new Dict.ExpandFailedException(expiringKeys);
        }
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

    // --- LRU (evict.c) ---

    /**
     * OBJECT IDLETIME 의 초 - estimateObjectIdleTime / 1000
     * 24비트 LRU 시계가 한 바퀴 돌았으면 넘어간 만큼 더함, 없는 키는 -1
     * 조회 없이 읽음 - 호출 측이 NOTOUCH 조회로 존재 확인 후 사용
     */
    public long idleSeconds(Key key) {
        Slot slot = dict.peek(key);
        if (slot == null) {
            return -1;
        }
        long lru = lruOf(slot);
        long idleMillis = lruClock >= lru
                ? (lruClock - lru) * LRU_CLOCK_RESOLUTION
                : (lruClock + (LRU_CLOCK_MAX - lru)) * LRU_CLOCK_RESOLUTION;
        return idleMillis / 1000;
    }

    /** 공유 정수면 공유 lru 표, 아니면 칸의 lru */
    private int lruOf(Slot slot) {
        int shared = sharedIndex(slot.value);
        return shared >= 0 ? sharedIntegerLru[shared] : slot.lru;
    }

    private void setLru(Slot slot, int lru) {
        int shared = sharedIndex(slot.value);
        if (shared >= 0) {
            sharedIntegerLru[shared] = lru;
        } else {
            slot.lru = lru;
        }
    }

    private static int sharedIndex(Value value) {
        return value instanceof StringValue string ? string.sharedIntegerIndex() : -1;
    }

    // --- 내부 ---

    /** initObjectLRUOrLFU - 공유 객체는 건드리지 않음 */
    private Slot newSlot(Value value) {
        Slot slot = new Slot(value);
        if (sharedIndex(value) < 0) {
            slot.lru = lruClock;
        }
        return slot;
    }

    /** dbSetValue 의 값 교체 - 새 값의 lru 는 옛 값의 lru (공유 정수면 공유 표에 씀) */
    private void setValueKeepingLru(Slot slot, Value value) {
        int oldLru = lruOf(slot);
        slot.value = value;
        setLru(slot, oldLru);
    }

    /** dbAddInternal(update_if_existing) - dictAddRaw 한 번, 있었으면 true */
    private boolean addOrUpdate(Key key, Value value) {
        Slot existing = dict.putIfAbsent(key, newSlot(value));
        if (existing == null) {
            return false;
        }
        setValueKeepingLru(existing, value);
        return true;
    }

    /** dbGenericDelete - 본 dict 에서 한 번, expires 가 비지 않았으면 거기서도 한 번 */
    private boolean deleteEntry(Key key) {
        if (!dict.remove(key)) {
            return false;
        }
        if (expires.size() > 0) {
            expires.remove(key);
        }
        forgetLookedUp();
        return true;
    }

    private void forgetLookedUp() {
        lookedUpKey = null;
        lookedUp = null;
    }

    // --- 테스트 - 연산별 rehash 기회 확인 ---

    Dict<?> mainDictForTest() {
        return dict;
    }

    Dict<Long> expiresForTest() {
        return expires;
    }
}
