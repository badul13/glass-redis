package glassredis.persistence;

import glassredis.command.CommandRegistry;
import glassredis.store.Entry;
import glassredis.store.HashValue;
import glassredis.store.Key;
import glassredis.store.Keyspace;
import glassredis.store.ListValue;
import glassredis.store.SetValue;
import glassredis.store.SortedSetValue;
import glassredis.store.StringValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AofLoaderTest {

    @TempDir
    Path dir;

    private final Keyspace keyspace = new Keyspace();

    private static String cmd(String... args) {
        StringBuilder out = new StringBuilder("*" + args.length + "\r\n");
        for (String arg : args) {
            out.append('$').append(arg.getBytes(StandardCharsets.UTF_8).length).append("\r\n").append(arg).append("\r\n");
        }
        return out.toString();
    }

    private AofManifest files(String base, String... incrs) throws IOException {
        AofManifest manifest = new AofManifest();
        Files.writeString(dir.resolve(manifest.newBase(false)), base);
        for (String incr : incrs) {
            Files.writeString(dir.resolve(manifest.newIncr()), incr);
        }
        return manifest;
    }

    private AofLoader.Result load(AofManifest manifest) {
        return new AofLoader(dir, CommandRegistry.withBuiltins(), keyspace).load(manifest);
    }

    private String get(String key) {
        Entry entry = keyspace.peek(Key.of(key));
        return entry == null ? null : new String(((StringValue) entry.value()).bytes(), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("base 다음 incr 순서로 다시 실행 - SELECT 0·주석 처리")
    void baseThenIncr() throws IOException {
        AofManifest manifest = files(cmd("SELECT", "0") + cmd("SET", "a", "base"),
                "#TS:1700000000\r\n" + cmd("SELECT", "0") + cmd("SET", "a", "incr") + cmd("RPUSH", "l", "x"));
        AofLoader.Result result = load(manifest);
        assertEquals(AofLoader.Status.OK, result.status());
        assertEquals(3, result.commands());
        assertEquals("incr", get("a"));
    }

    @Test
    @DisplayName("지난 만료 시각도 그대로 복구 - 로딩 중에는 안 지움")
    void keepsExpiredKeysWhileLoading() throws IOException {
        load(files(cmd("SET", "k", "v") + cmd("PEXPIREAT", "k", "1")));
        assertEquals(1, keyspace.size());
        assertEquals(1, keyspace.peek(Key.of("k")).expireAtMillis());
        assertNull(keyspace.get(Key.of("k")), "로딩 끝난 뒤 접근하면 만료");
    }

    @Test
    @DisplayName("마지막 명령이 반쯤 쓰였으면 그 앞에서 잘라내고 계속")
    void truncatedTail() throws IOException {
        String good = cmd("SET", "a", "1");
        AofManifest manifest = files("", good + "*3\r\n$3\r\nSET\r\n$4\r\nhalf\r\n$5\r\nval");
        AofLoader.Result result = load(manifest);
        assertEquals(AofLoader.Status.TRUNCATED, result.status());
        assertEquals("appendonly.aof.1.incr.aof", result.truncatedFile());
        assertEquals(good.length(), Files.size(dir.resolve("appendonly.aof.1.incr.aof")));
        assertEquals("1", get("a"));
        assertNull(get("half"));
    }

    @Test
    @DisplayName("EXEC 없이 끝난 MULTI 는 MULTI 앞까지 되돌림")
    void incompleteTransaction() throws IOException {
        String good = cmd("SET", "a", "1");
        AofManifest manifest = files("", good + cmd("MULTI") + cmd("SET", "q", "1"));
        assertEquals(AofLoader.Status.TRUNCATED, load(manifest).status());
        assertEquals(good.length(), Files.size(dir.resolve("appendonly.aof.1.incr.aof")));
        assertNull(get("q"));
    }

    @Test
    @DisplayName("MULTI/EXEC 안 명령은 EXEC 에서 실행")
    void completeTransaction() throws IOException {
        load(files("", cmd("MULTI") + cmd("DEL", "y") + cmd("SET", "y", "2") + cmd("EXEC")));
        assertEquals("2", get("y"));
    }

    @Test
    @DisplayName("잘린 파일이 마지막이 아니면 실패 - 뒤 파일과 이어지지 않음")
    void truncatedNotLast() throws IOException {
        AofManifest manifest = files("", cmd("SET", "a", "1") + "*2\r\n$3\r\nGET", cmd("SET", "b", "2"));
        AofLoader.Result result = load(manifest);
        assertEquals(AofLoader.Status.FAILED, result.status());
    }

    @Test
    @DisplayName("모르는 명령·형식 오류·다른 DB 는 실패")
    void failures() throws IOException {
        assertEquals(AofLoader.Status.FAILED, load(files(cmd("NOPE", "x"))).status());
        assertEquals(AofLoader.Status.FAILED, load(files("SET a 1\r\n")).status());
        assertEquals(AofLoader.Status.FAILED, load(files(cmd("SELECT", "1"))).status());
    }

    /** RDB 형식 base + 명령 incr - source 를 base 로 */
    private AofManifest rdbBase(Keyspace source, String incr) throws IOException {
        AofManifest manifest = new AofManifest();
        Files.write(dir.resolve(manifest.newBase(true)), RdbWriter.write(source, 1, 1, true));
        Files.writeString(dir.resolve(manifest.newIncr()), incr);
        return manifest;
    }

    @Test
    @DisplayName("RDB base - 키는 바로 넣고 incr 명령만 재실행")
    void rdbBaseThenIncr() throws IOException {
        Keyspace source = new Keyspace();
        source.put(Key.of("a"), Entry.of("base".getBytes(StandardCharsets.UTF_8)));
        source.put(Key.of("b"), Entry.of("kept".getBytes(StandardCharsets.UTF_8)));
        AofLoader.Result result = load(rdbBase(source, cmd("SELECT", "0") + cmd("SET", "a", "incr")));
        assertEquals(AofLoader.Status.OK, result.status());
        assertEquals(2, result.rdbKeys());
        assertEquals(1, result.commands());
        assertEquals("incr", get("a"));
        assertEquals("kept", get("b"));
    }

    @Test
    @DisplayName("Redis 7.2.4 가 쓴 base.rdb - 자료형별 작은 인코딩 그대로, 지난 만료 키 gone 도 넣음")
    void redisBase() throws IOException {
        // Docker redis:7.2.4-alpine appendonly yes, 키 8개(만료 2개) 뒤 BGREWRITEAOF 의 appendonly.aof.4.base.rdb
        // gone - PX 60000, 2026-10-23 기준이라 지금은 지난 만료
        String hex = "524544495330303131fa0972656469732d76657205372e322e34fa0a72656469732d62697473c040fa056374696d65c2c8f9da6afa08757365642d6d656dc220961100fa08616f662d62617365c001fe00fb0802100468617368151500000004008266310382763103826632030201ff00036e756dc139300b04696e7473140400000003000000fdffffff050000007011010011047a7365741c1c0000000600816102010181620283322e350481630283696e6604ff1402736c0e0e000000020081780282797903ff12046c6973740102121200000004008161028162020101dffe02fffc88a06067a10100000004676f6e650176fc00d8c32cbb03000000037374720568656c6c6fff24e0819a1f489f41";
        AofManifest manifest = new AofManifest();
        Files.write(dir.resolve(manifest.newBase(true)), HexFormat.of().parseHex(hex));
        Files.writeString(dir.resolve(manifest.newIncr()), cmd("SELECT", "0") + cmd("RPUSH", "list", "z"));

        AofLoader.Result result = load(manifest);
        assertEquals(AofLoader.Status.OK, result.status());
        assertEquals(8, result.rdbKeys());
        assertEquals(1, result.commands());
        assertEquals(8, keyspace.size());
        assertNotNull(keyspace.peek(Key.of("gone")), "지난 만료 키도 로딩 직후에는 있음");
        assertNull(keyspace.get(Key.of("gone")), "로딩 끝난 뒤 접근하면 만료");

        assertEquals("hello", get("str"));
        assertEquals(4102444800000L, keyspace.peek(Key.of("str")).expireAtMillis());
        assertEquals("12345", get("num"));
        assertEquals(5, ((ListValue) keyspace.peek(Key.of("list")).value()).size(), "base 4개 + incr RPUSH");
        assertNotNull(((SetValue) keyspace.peek(Key.of("ints")).value()).intset(), "intset 덩어리 그대로");
        assertEquals(2, ((SetValue) keyspace.peek(Key.of("sl")).value()).size());
        assertEquals("2", new String(((HashValue) keyspace.peek(Key.of("hash")).value()).get(Key.of("f2")),
                StandardCharsets.UTF_8));
        SortedSetValue zset = (SortedSetValue) keyspace.peek(Key.of("zset")).value();
        assertEquals(2.5, zset.score(Key.of("b")));
        assertEquals(Double.POSITIVE_INFINITY, zset.score(Key.of("c")));
    }

    @Test
    @DisplayName("RDB base 의 지난 만료 키도 넣음 - dump.rdb 와 달리 버리지 않음 (RDBFLAGS_AOF_PREAMBLE)")
    void rdbBaseKeepsExpiredKeys() throws IOException {
        Keyspace source = new Keyspace();
        source.put(Key.of("k"), new Entry(StringValue.of("v".getBytes(StandardCharsets.UTF_8)), 1));
        load(rdbBase(source, ""));
        assertEquals(1, keyspace.size());
        assertNull(keyspace.get(Key.of("k")), "로딩 끝난 뒤 접근하면 만료");
    }

    @Test
    @DisplayName("RDB 뒤에 이어진 명령도 실행 - Redis 6 단일 파일 preamble 형식")
    void rdbBaseWithTail() throws IOException {
        Keyspace source = new Keyspace();
        source.put(Key.of("a"), Entry.of("rdb".getBytes(StandardCharsets.UTF_8)));
        AofManifest manifest = new AofManifest();
        byte[] rdb = RdbWriter.write(source, 1, 1, true);
        byte[] tail = cmd("SET", "t", "tail").getBytes(StandardCharsets.US_ASCII);
        byte[] both = Arrays.copyOf(rdb, rdb.length + tail.length);
        System.arraycopy(tail, 0, both, rdb.length, tail.length);
        Files.write(dir.resolve(manifest.newBase(true)), both);
        assertEquals(AofLoader.Status.OK, load(manifest).status());
        assertEquals("rdb", get("a"));
        assertEquals("tail", get("t"));
    }

    @Test
    @DisplayName("잘린 RDB base 는 잘라내기 없이 실패")
    void truncatedRdbBase() throws IOException {
        Keyspace source = new Keyspace();
        source.put(Key.of("a"), Entry.of("base".getBytes(StandardCharsets.UTF_8)));
        AofManifest manifest = new AofManifest();
        byte[] rdb = RdbWriter.write(source, 1, 1, true);
        Path base = dir.resolve(manifest.newBase(true));
        Files.write(base, Arrays.copyOf(rdb, rdb.length - 3));
        AofLoader.Result result = load(manifest);
        assertEquals(AofLoader.Status.FAILED, result.status());
        assertEquals(rdb.length - 3, Files.size(base), "파일은 그대로");
    }

    @Test
    @DisplayName("파일이 전부 비면 EMPTY, manifest 가 비면 NOT_EXIST")
    void emptyAndMissing() throws IOException {
        assertEquals(AofLoader.Status.EMPTY, load(files("", "")).status());
        assertEquals(AofLoader.Status.NOT_EXIST, load(new AofManifest()).status());
        assertTrue(keyspace.size() == 0);
    }
}
