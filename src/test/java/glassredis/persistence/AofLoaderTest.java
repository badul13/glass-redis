package glassredis.persistence;

import glassredis.command.CommandRegistry;
import glassredis.store.Entry;
import glassredis.store.Key;
import glassredis.store.Keyspace;
import glassredis.store.StringValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        Files.writeString(dir.resolve(manifest.newBase()), base);
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

    @Test
    @DisplayName("파일이 전부 비면 EMPTY, manifest 가 비면 NOT_EXIST")
    void emptyAndMissing() throws IOException {
        assertEquals(AofLoader.Status.EMPTY, load(files("", "")).status());
        assertEquals(AofLoader.Status.NOT_EXIST, load(new AofManifest()).status());
        assertTrue(keyspace.size() == 0);
    }
}
