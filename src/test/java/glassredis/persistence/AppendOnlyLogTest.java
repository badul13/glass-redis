package glassredis.persistence;

import glassredis.command.CommandRegistry;
import glassredis.observe.AofSnapshot;
import glassredis.observe.EventBus;
import glassredis.store.Entry;
import glassredis.store.Key;
import glassredis.store.Keyspace;
import glassredis.store.StringValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(10)
class AppendOnlyLogTest {

    @TempDir
    Path dir;

    private final List<AppendOnlyLog> opened = new ArrayList<>();

    @AfterEach
    void closeAll() {
        opened.forEach(AppendOnlyLog::close);
    }

    private AppendOnlyLog open(Keyspace keyspace, int percentage, long minSize) throws IOException {
        return open(keyspace, percentage, minSize, false);
    }

    private AppendOnlyLog open(Keyspace keyspace, int percentage, long minSize, boolean rdbPreamble)
            throws IOException {
        AppendOnlyLog aof = new AppendOnlyLog(new AppendOnlyLog.Config(dir, true,
                AppendOnlyLog.FsyncPolicy.EVERYSEC, percentage, minSize, rdbPreamble), EventBus.NONE);
        aof.start(keyspace, CommandRegistry.withBuiltins());
        opened.add(aof);
        return aof;
    }

    private static List<byte[]> argv(String... args) {
        List<byte[]> out = new ArrayList<>();
        for (String arg : args) {
            out.add(arg.getBytes(StandardCharsets.UTF_8));
        }
        return out;
    }

    private String file(String name) throws IOException {
        return Files.readString(dir.resolve(AppendOnlyLog.DIRNAME).resolve(name));
    }

    @Test
    @DisplayName("처음 기동 - 빈 base, 빈 incr, manifest 생성")
    void firstStartCreatesFiles() throws IOException {
        open(new Keyspace(), 100, 64L << 20);
        assertEquals("", file("appendonly.aof.1.base.aof"));
        assertEquals("", file("appendonly.aof.1.incr.aof"));
        assertEquals("file appendonly.aof.1.base.aof seq 1 type b\nfile appendonly.aof.1.incr.aof seq 1 type i\n",
                file(AofManifest.MANIFEST_NAME));
    }

    @Test
    @DisplayName("feed 는 버퍼만, flush 에서 파일로 - 첫 명령 앞 SELECT 0, 여러 개면 MULTI/EXEC")
    void feedThenFlush() throws IOException {
        AppendOnlyLog aof = open(new Keyspace(), 100, 64L << 20);
        aof.feedAll(List.of(argv("SET", "a", "1")));
        assertEquals("", file("appendonly.aof.1.incr.aof"), "flush 전에는 파일에 없음");
        aof.flush();
        aof.feedAll(List.of(argv("DEL", "y"), argv("SET", "y", "2")));
        aof.flush();
        assertEquals("*2\r\n$6\r\nSELECT\r\n$1\r\n0\r\n*3\r\n$3\r\nSET\r\n$1\r\na\r\n$1\r\n1\r\n"
                        + "*1\r\n$5\r\nMULTI\r\n*2\r\n$3\r\nDEL\r\n$1\r\ny\r\n*3\r\n$3\r\nSET\r\n$1\r\ny\r\n$1\r\n2\r\n"
                        + "*1\r\n$4\r\nEXEC\r\n",
                file("appendonly.aof.1.incr.aof"));
    }

    @Test
    @DisplayName("재시작하면 base + incr 를 다시 실행해 복구, 이어서 같은 incr 에 덧붙임")
    void restartRecovers() throws IOException {
        Keyspace first = new Keyspace();
        AppendOnlyLog aof = open(first, 100, 64L << 20);
        aof.feedAll(List.of(argv("SET", "a", "1")));
        aof.close();
        opened.clear();

        Keyspace second = new Keyspace();
        AppendOnlyLog reopened = open(second, 100, 64L << 20);
        assertEquals(AofLoader.Status.OK, reopened.loadResult().status());
        Entry a = second.peek(Key.of("a"));
        assertNotNull(a);
        assertEquals("1", new String(((StringValue) a.value()).bytes(), StandardCharsets.UTF_8));

        reopened.feedAll(List.of(argv("SET", "b", "2")));
        reopened.flush();
        assertTrue(file("appendonly.aof.1.incr.aof").endsWith("$1\r\nb\r\n$1\r\n2\r\n"));
        assertEquals(2, file("appendonly.aof.1.incr.aof").split("SELECT").length - 1, "재시작 뒤 첫 명령 앞 SELECT 다시");
    }

    @Test
    @DisplayName("BGREWRITEAOF - 새 incr 로 전환, 끝나면 새 base 와 새 incr 만 남음")
    void backgroundRewrite() throws Exception {
        Keyspace keyspace = new Keyspace();
        AppendOnlyLog aof = open(keyspace, 100, 64L << 20);
        for (int i = 0; i < 50; i++) {
            keyspace.put(Key.of("k"), Entry.of(("" + i).getBytes(StandardCharsets.UTF_8)));
            aof.feedAll(List.of(argv("SET", "k", "" + i)));
        }
        aof.flush();

        assertEquals(null, aof.rewriteInBackground(keyspace, "test"));
        assertEquals("Background append only file rewriting already in progress",
                aof.rewriteInBackground(keyspace, "test"));
        aof.feedAll(List.of(argv("SET", "during", "rewrite")));
        aof.flush();

        while (aof.rewriting()) {
            aof.checkRewriteDone();
            Thread.sleep(10);
        }
        assertEquals("file appendonly.aof.2.base.aof seq 2 type b\nfile appendonly.aof.2.incr.aof seq 2 type i\n",
                file(AofManifest.MANIFEST_NAME));
        assertEquals("*2\r\n$6\r\nSELECT\r\n$1\r\n0\r\n*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$2\r\n49\r\n",
                file("appendonly.aof.2.base.aof"));
        assertTrue(file("appendonly.aof.2.incr.aof").contains("during"), "재작성 중 쓰기는 새 incr 에");
        Path aofDir = dir.resolve(AppendOnlyLog.DIRNAME);
        assertFalse(Files.exists(aofDir.resolve("appendonly.aof.1.base.aof")), "옛 base 삭제");
        assertFalse(Files.exists(aofDir.resolve("appendonly.aof.1.incr.aof")), "옛 incr 삭제");

        AofSnapshot snapshot = aof.snapshot();
        assertEquals(snapshot.currentSize(), snapshot.baseSize(), "재작성 직후 크기가 다음 증가율 기준");
    }

    @Test
    @DisplayName("자동 재작성 - min-size 를 넘고 기준 대비 percentage% 커지면")
    void automaticRewrite() throws Exception {
        Keyspace keyspace = new Keyspace();
        AppendOnlyLog aof = open(keyspace, 100, 200);
        keyspace.put(Key.of("counter"), Entry.of("1".getBytes(StandardCharsets.UTF_8)));
        aof.autoRewrite(keyspace);
        assertFalse(aof.rewriting(), "200바이트 이하라 안 함");

        for (int i = 0; i < 20; i++) {
            aof.feedAll(List.of(argv("INCR", "counter")));
        }
        aof.flush();
        aof.autoRewrite(keyspace);
        assertTrue(aof.rewriting());
    }

    private byte[] bytes(String name) throws IOException {
        return Files.readAllBytes(dir.resolve(AppendOnlyLog.DIRNAME).resolve(name));
    }

    @Test
    @DisplayName("preamble yes - 처음 base 부터 .rdb, 재작성도 RDB, 재시작하면 RDB 키 + incr 명령으로 복구")
    void rdbPreamble() throws Exception {
        Keyspace keyspace = new Keyspace();
        AppendOnlyLog aof = open(keyspace, 100, 64L << 20, true);
        assertEquals("file appendonly.aof.1.base.rdb seq 1 type b\nfile appendonly.aof.1.incr.aof seq 1 type i\n",
                file(AofManifest.MANIFEST_NAME));
        assertEquals("REDIS0011", new String(bytes("appendonly.aof.1.base.rdb"), 0, 9, StandardCharsets.US_ASCII),
                "빈 데이터도 RDB 헤더·EOF·체크섬");

        keyspace.put(Key.of("k"), Entry.of("v".getBytes(StandardCharsets.UTF_8)));
        aof.feedAll(List.of(argv("SET", "k", "v")));
        aof.flush();
        assertEquals(null, aof.rewriteInBackground(keyspace, "test"));
        while (aof.rewriting()) {
            aof.checkRewriteDone();
            Thread.sleep(10);
        }
        aof.feedAll(List.of(argv("SET", "after", "rewrite")));
        aof.close();
        opened.clear();
        assertEquals("file appendonly.aof.2.base.rdb seq 2 type b\nfile appendonly.aof.2.incr.aof seq 2 type i\n",
                file(AofManifest.MANIFEST_NAME));
        assertFalse(Files.exists(dir.resolve(AppendOnlyLog.DIRNAME).resolve("appendonly.aof.1.base.rdb")), "옛 base 삭제");

        Keyspace second = new Keyspace();
        AppendOnlyLog reopened = open(second, 100, 64L << 20, true);
        AofLoader.Result loaded = reopened.loadResult();
        assertEquals(AofLoader.Status.OK, loaded.status());
        assertEquals(1, loaded.rdbKeys());
        assertEquals(1, loaded.commands());
        assertEquals(2, second.size());
    }

    @Test
    @DisplayName("preamble 을 껐다 켜도 복구 - 형식은 확장자가 아니라 파일 앞 \"REDIS\" 로 판단")
    void switchPreamble() throws Exception {
        Keyspace keyspace = new Keyspace();
        AppendOnlyLog aof = open(keyspace, 100, 64L << 20, false);
        keyspace.put(Key.of("k"), Entry.of("v".getBytes(StandardCharsets.UTF_8)));
        aof.feedAll(List.of(argv("SET", "k", "v")));
        aof.close();
        opened.clear();

        Keyspace second = new Keyspace();
        AppendOnlyLog reopened = open(second, 100, 64L << 20, true);
        assertEquals(1, second.size());
        assertEquals(null, reopened.rewriteInBackground(second, "test"));
        while (reopened.rewriting()) {
            reopened.checkRewriteDone();
            Thread.sleep(10);
        }
        reopened.close();
        opened.clear();

        Keyspace third = new Keyspace();
        open(third, 100, 64L << 20, false);
        assertEquals(1, third.size());
    }
}
