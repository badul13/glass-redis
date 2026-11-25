package glassredis.store;

import glassredis.command.CommandRegistry;
import glassredis.command.Context;
import glassredis.store.encoding.Dict;
import glassredis.store.encoding.DictProbe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 명령마다 dict 를 건드리는 횟수(_dictRehashStep 기회)가 Redis 7.2.4 와 같은지
 * 기대값 근거 - db.c lookupKey·setKey·dbAdd·dbSetValue·setExpire·removeExpire·dbGenericDelete·getExpire,
 * t_string.c·expire.c·t_hash.c·t_set.c·t_zset.c 의 명령 본문 (각 테스트 주석에 호출 순서)
 * 키스페이스 dict 와 expires 둘 다 rehash 중이고 expires 가 비지 않은 상태에서 잼
 */
class RehashStepsTest {

    private final ManualClock clock = new ManualClock(1_000_000_000L);
    private final Keyspace keyspace = new Keyspace(clock);
    private final Context ctx = new Context(keyspace);
    private final CommandRegistry registry = CommandRegistry.withBuiltins();

    @BeforeEach
    void fill() {
        for (int i = 0; i < 300; i++) {
            run("SET", "k" + i, "v");
            run("SET", "e" + i, "v", "EX", "1000");
        }
        run("SET", "counter", "100000");
        run("SET", "small", "5");
        run("SET", "word", "hello");
        startRehash();
    }

    @Test
    @DisplayName("GET - lookupKeyRead: dict 1, expires 1 (keyIsExpired → getExpire), 없는 키는 dict 만")
    void get() {
        assertSteps(1, 1, "GET", "k1");
        assertSteps(1, 0, "GET", "missing");
    }

    @Test
    @DisplayName("SET 기존 키 - lookupKeyWrite(1,1) + dbSetValue dictFind(1) + removeExpire(0,1)")
    void setExisting() {
        assertSteps(2, 2, "SET", "k1", "x");
    }

    @Test
    @DisplayName("SET 새 키 - lookupKeyWrite 빈손(1,0) + dbAdd(1) + removeExpire(0,1)")
    void setNew() {
        assertSteps(2, 1, "SET", "n1", "x");
    }

    @Test
    @DisplayName("SET 새 키 EX - KEEPTTL 이라 removeExpire 없음, setExpire 가 dictFind(1) + dictAddOrFind(0,1)")
    void setNewWithExpire() {
        assertSteps(3, 1, "SET", "n2", "x", "EX", "100");
    }

    @Test
    @DisplayName("SET GET - getGenericCommand 의 lookupKeyRead(1,1) 가 앞에 더해짐")
    void setGet() {
        assertSteps(3, 3, "SET", "k1", "x", "GET");
    }

    @Test
    @DisplayName("MSET - 키마다 setKey(flags 0): lookupKeyWrite(1,1) + dbSetValue(1) + removeExpire(0,1)")
    void mset() {
        assertSteps(4, 4, "MSET", "k1", "a", "k2", "b");
    }

    @Test
    @DisplayName("DEL - expireIfNeeded 의 getExpire(0,1) + dbGenericDelete dict(1)·expires(0,1)")
    void del() {
        assertSteps(1, 2, "DEL", "k1");
    }

    @Test
    @DisplayName("EXISTS·TYPE - NOTOUCH 조회 (1,1)")
    void existsAndType() {
        assertSteps(1, 1, "EXISTS", "k1");
        assertSteps(1, 1, "TYPE", "k1");
    }

    @Test
    @DisplayName("TTL - NOTOUCH 조회(1,1) 뒤 getExpire 한 번 더(0,1)")
    void ttl() {
        assertSteps(1, 2, "TTL", "e1");
    }

    @Test
    @DisplayName("EXPIRE - lookupKeyWrite(1,1) + setExpire(1,1), 조건 옵션이면 getExpire(0,1) 추가")
    void expire() {
        assertSteps(2, 2, "EXPIRE", "k1", "100");
        assertSteps(2, 3, "EXPIRE", "k2", "100", "NX");
    }

    @Test
    @DisplayName("PERSIST - lookupKeyWrite(1,1) + removeExpire(0,1)")
    void persist() {
        assertSteps(1, 2, "PERSIST", "e1");
    }

    @Test
    @DisplayName("KEYS - 본 dict 는 safe iterator 라 0, 패턴에 맞는 키마다 keyIsExpired 의 expires dictFind")
    void keys() {
        // k10..k19 - 10개
        assertSteps(0, 10, "KEYS", "k1?");
    }

    @Test
    @DisplayName("INCR - 공유 아닌 int 는 제자리 수정이라 조회(1,1)만, 공유 정수 결과면 dbReplaceValue(1) 추가")
    void incr() {
        assertSteps(1, 1, "INCR", "counter");
        assertSteps(2, 1, "INCR", "small");
    }

    @Test
    @DisplayName("APPEND - embstr 은 raw 새 객체로 dbReplaceValue(1) 추가, 이미 raw 면 제자리라 조회(1,1)만")
    void append() {
        assertSteps(2, 1, "APPEND", "word", "!");
        assertSteps(1, 1, "APPEND", "word", "!");
    }

    @Test
    @DisplayName("hashtable Hash - 새 필드·기존 필드 모두 dictAddRaw 한 번, HGET dictFind 한 번, HINCRBY 는 찾기+넣기")
    void hashFields() {
        Dict<?> fields = bigHash();
        assertValueSteps(fields, 1, "HSET", "h", "new", "1");
        assertValueSteps(fields, 1, "HSET", "h", "f1", "2");
        assertValueSteps(fields, 1, "HGET", "h", "f2");
        run("HSET", "h", "num", "10");
        assertValueSteps(fields, 2, "HINCRBY", "h", "num", "1");
        assertValueSteps(fields, 1, "HDEL", "h", "f4");
    }

    @Test
    @DisplayName("skiplist Sorted Set - 점수 갱신·같은 점수 dictFind 한 번, 새 멤버 찾기+추가, ZREM dictUnlink 한 번")
    void zsetMembers() {
        Dict<?> members = bigZset();
        assertValueSteps(members, 1, "ZADD", "z", "500", "m1");
        assertValueSteps(members, 1, "ZADD", "z", "500", "m1");
        assertValueSteps(members, 2, "ZADD", "z", "1", "brand-new");
        assertValueSteps(members, 1, "ZINCRBY", "z", "1", "m2");
        assertValueSteps(members, 1, "ZSCORE", "z", "m3");
        assertValueSteps(members, 1, "ZRANK", "z", "m4", "WITHSCORE");
        assertValueSteps(members, 1, "ZREM", "z", "m5");
    }

    @Test
    @DisplayName("hashtable Set - SADD 새 원소 dictFindPositionForInsert 한 번, SISMEMBER dictFind, SREM dictDelete")
    void setMembers() {
        Dict<?> elements = bigSet();
        assertValueSteps(elements, 1, "SADD", "s", "brand-new");
        assertValueSteps(elements, 1, "SADD", "s", "x1");
        assertValueSteps(elements, 1, "SISMEMBER", "s", "x2");
        assertValueSteps(elements, 1, "SREM", "s", "x3");
    }

    // --- 도우미 ---

    private void startRehash() {
        DictProbe.startRehash(keyspace.mainDictForTest());
        DictProbe.startRehash(keyspace.expiresForTest());
        assertTrue(keyspace.mainDictForTest().isRehashing());
        assertTrue(keyspace.expiresForTest().isRehashing());
    }

    private Dict<?> bigHash() {
        List<String> argv = new ArrayList<>(List.of("HSET", "h"));
        for (int i = 0; i < 600; i++) {
            argv.add("f" + i);
            argv.add("v");
        }
        run(argv.toArray(String[]::new));
        Dict<?> dict = ((HashValue) keyspace.peek(Key.of("h")).value()).dict();
        DictProbe.startRehash(dict);
        return dict;
    }

    private Dict<?> bigZset() {
        List<String> argv = new ArrayList<>(List.of("ZADD", "z"));
        for (int i = 0; i < 600; i++) {
            argv.add(Integer.toString(i));
            argv.add("m" + i);
        }
        run(argv.toArray(String[]::new));
        Dict<?> dict = ((SortedSetValue) keyspace.peek(Key.of("z")).value()).dict();
        DictProbe.startRehash(dict);
        return dict;
    }

    private Dict<?> bigSet() {
        List<String> argv = new ArrayList<>(List.of("SADD", "s"));
        for (int i = 0; i < 600; i++) {
            argv.add("x" + i);
        }
        run(argv.toArray(String[]::new));
        Dict<?> dict = ((SetValue) keyspace.peek(Key.of("s")).value()).dict();
        DictProbe.startRehash(dict);
        return dict;
    }

    /** 키스페이스 dict·expires 의 rehash 기회 횟수 */
    private void assertSteps(long main, long expires, String... argv) {
        long mainBefore = DictProbe.rehashSteps(keyspace.mainDictForTest());
        long expiresBefore = DictProbe.rehashSteps(keyspace.expiresForTest());
        run(argv);
        String command = String.join(" ", argv);
        assertEquals(main, DictProbe.rehashSteps(keyspace.mainDictForTest()) - mainBefore, command + " - 본 dict");
        assertEquals(expires, DictProbe.rehashSteps(keyspace.expiresForTest()) - expiresBefore,
                command + " - expires");
    }

    /** 값 안의 dict(필드·멤버) rehash 기회 횟수 */
    private void assertValueSteps(Dict<?> dict, long expected, String... argv) {
        long before = DictProbe.rehashSteps(dict);
        run(argv);
        assertEquals(expected, DictProbe.rehashSteps(dict) - before, String.join(" ", argv));
    }

    private void run(String... argv) {
        List<byte[]> all = new ArrayList<>();
        for (String arg : argv) {
            all.add(arg.getBytes(StandardCharsets.UTF_8));
        }
        keyspace.updateCommandTime();
        ctx.propagation().begin(all);
        registry.find(argv[0].toUpperCase(Locale.ROOT)).execute(ctx, all.subList(1, all.size()));
        ctx.propagation().finish();
    }
}
