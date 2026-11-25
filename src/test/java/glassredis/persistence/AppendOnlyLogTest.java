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
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        // 첫 flush 가 맡긴 fsync 가 아직이면 everysec 은 write 를 미룸 - 내용 확인용으로 강제
        aof.flush(true);
        assertEquals("*2\r\n$6\r\nSELECT\r\n$1\r\n0\r\n*3\r\n$3\r\nSET\r\n$1\r\na\r\n$1\r\n1\r\n"
                        + "*1\r\n$5\r\nMULTI\r\n*2\r\n$3\r\nDEL\r\n$1\r\ny\r\n*3\r\n$3\r\nSET\r\n$1\r\ny\r\n$1\r\n2\r\n"
                        + "*1\r\n$4\r\nEXEC\r\n",
                file("appendonly.aof.1.incr.aof"));
    }

    private static final String SET_A = "*2\r\n$6\r\nSELECT\r\n$1\r\n0\r\n*3\r\n$3\r\nSET\r\n$1\r\na\r\n$1\r\n1\r\n";

    private static FaultyChannel faulty(AppendOnlyLog aof) {
        FaultyChannel channel = new FaultyChannel(aof.swapChannel(null));
        aof.swapChannel(channel);
        return channel;
    }

    @Test
    @DisplayName("일부만 쓰인 AOF - truncate 로 제거, MISCONF 사유 유지, 재시도 시 전체 기록 후 해제")
    void shortWriteIsTruncatedAndRetried() throws IOException {
        AppendOnlyLog aof = open(new Keyspace(), 100, 64L << 20);
        FaultyChannel channel = faulty(aof);
        channel.failAfter = 5;
        aof.feedAll(List.of(argv("SET", "a", "1")));
        aof.flush(true);

        assertEquals("", file("appendonly.aof.1.incr.aof"), "일부 기록 잘라냄");
        assertEquals("No space left on device", aof.writeError());

        channel.failAfter = -1;
        aof.retryNowForTest();
        assertEquals(SET_A, file("appendonly.aof.1.incr.aof"));
        assertEquals(null, aof.writeError());
    }

    @Test
    @DisplayName("truncate 도 실패 - 쓴 만큼 버퍼에서 빼고 나머지만 재기록 (중복 없음)")
    void shortWriteKeptWhenTruncateFails() throws IOException {
        AppendOnlyLog aof = open(new Keyspace(), 100, 64L << 20);
        FaultyChannel channel = faulty(aof);
        channel.failAfter = 5;
        channel.failTruncate = true;
        aof.feedAll(List.of(argv("SET", "a", "1")));
        aof.flush(true);
        assertEquals(SET_A.substring(0, 5), file("appendonly.aof.1.incr.aof"));

        channel.failAfter = -1;
        aof.retryNowForTest();
        assertEquals(SET_A, file("appendonly.aof.1.incr.aof"));
        assertEquals(null, aof.writeError());
    }

    @Test
    @DisplayName("한 바이트도 못 씀 - 버퍼 유지, 사유는 예외 메시지")
    void failedWriteKeepsBuffer() throws IOException {
        AppendOnlyLog aof = open(new Keyspace(), 100, 64L << 20);
        FaultyChannel channel = faulty(aof);
        channel.failAfter = 0;
        aof.feedAll(List.of(argv("SET", "a", "1")));
        aof.flush(true);
        assertEquals("", file("appendonly.aof.1.incr.aof"));
        assertNotNull(aof.writeError());

        channel.failAfter = -1;
        aof.retryNowForTest();
        assertEquals(SET_A, file("appendonly.aof.1.incr.aof"));
    }

    @Test
    @DisplayName("메시지 없는 예외도 오류 상태 - 동기화 뒤 재개하면 해제 (AOF reopen)")
    void messagelessErrorCountsAndResumeClears() throws IOException {
        AppendOnlyLog aof = open(new Keyspace(), 0, 64L << 20);
        FaultyChannel channel = faulty(aof);
        channel.failAfter = 0;
        channel.failure = new java.nio.channels.ClosedChannelException();
        aof.feedAll(List.of(argv("SET", "a", "1")));
        aof.flush(true);
        assertEquals("ClosedChannelException", aof.writeError());

        channel.failAfter = -1;
        aof.suspend();
        assertEquals(null, aof.writeError(), "AOF_OFF 동안 검사 안 함");
        assertEquals(null, aof.resumeAfterSync(new Keyspace(), true));
        assertEquals(null, aof.writeError(), "재개 시 이전 오류 해제");
    }

    @Test
    @DisplayName("재작성 연속 3번 실패 - 다음 자동·예약 재작성 제한, BGREWRITEAOF 예약은 제한 해제")
    void rewriteLimitedAfterThreeFailures() throws Exception {
        AppendOnlyLog aof = open(new Keyspace(), 0, 64L << 20);
        // 임시 base 자리를 비지 않은 폴더로 막아 쓰기 실패
        Path temp = dir.resolve("temp-rewriteaof-bg-" + ProcessHandle.current().pid() + ".aof");
        Files.createDirectories(temp.resolve("block"));
        for (int i = 0; i < 3; i++) {
            assertFalse(aof.rewriteLimited(), (i) + "번 실패까지는 제한 없음");
            assertEquals(null, aof.rewriteInBackground(new Keyspace(), "test"));
            while (aof.rewriting()) {
                aof.checkRewriteDone();
                Thread.sleep(10);
            }
        }
        assertEquals(3, aof.consecutiveRewriteFailures());
        assertTrue(aof.rewriteLimited(), "3번 실패 - 1분 대기");
        assertTrue(aof.rewriteLimited(), "대기 중");

        aof.scheduleRewrite();
        assertFalse(aof.rewriteLimited(), "수동 BGREWRITEAOF 예약은 실패 횟수 초기화");
    }

    @Test
    @DisplayName("appendfsync always 쓰기 실패 - 되돌릴 수 없어 종료")
    void alwaysPolicyStopsOnWriteError() throws IOException {
        AppendOnlyLog aof = new AppendOnlyLog(new AppendOnlyLog.Config(dir, true,
                AppendOnlyLog.FsyncPolicy.ALWAYS, 100, 64L << 20, false), EventBus.NONE);
        aof.start(new Keyspace(), CommandRegistry.withBuiltins());
        opened.add(aof);
        FaultyChannel channel = faulty(aof);
        channel.failAfter = 0;
        aof.feedAll(List.of(argv("SET", "a", "1")));
        assertThrows(IllegalStateException.class, () -> aof.flush(true));
        // 정리 단계 close() 의 flush 용
        channel.failAfter = -1;
    }

    @Test
    @DisplayName("레플리카 전체 동기화 뒤 - 재작성 끝날 때까지 manifest 밖 임시 incr, 끝나면 새 incr 로")
    void syncWritesToTempIncrUntilRewriteDone() throws Exception {
        AppendOnlyLog aof = open(new Keyspace(), 0, 64L << 20);
        aof.feedAll(List.of(argv("SET", "old", "1")));
        aof.flush(true);
        String oldIncr = file("appendonly.aof.1.incr.aof");

        aof.suspend();
        Keyspace synced = new Keyspace();
        synced.put(Key.of("fromMaster"), Entry.of("v".getBytes(StandardCharsets.UTF_8)));
        assertEquals(null, aof.resumeAfterSync(synced, false));
        aof.feedAll(List.of(argv("SET", "after", "sync")));
        aof.flush(true);

        assertEquals(oldIncr, file("appendonly.aof.1.incr.aof"), "옛 incr 에 덧붙지 않음");
        assertTrue(file(AppendOnlyLog.TEMP_INCR_NAME).contains("after"), "임시 incr 에 기록");
        assertFalse(file(AofManifest.MANIFEST_NAME).contains("temp-"), "임시 incr 는 manifest 밖");

        while (aof.rewriting()) {
            aof.checkRewriteDone();
            Thread.sleep(10);
        }
        assertEquals("file appendonly.aof.2.base.aof seq 2 type b\nfile appendonly.aof.2.incr.aof seq 2 type i\n",
                file(AofManifest.MANIFEST_NAME));
        assertTrue(file("appendonly.aof.2.incr.aof").contains("after"), "임시 incr 가 새 incr 로");
        assertFalse(Files.exists(dir.resolve(AppendOnlyLog.DIRNAME).resolve("appendonly.aof.1.incr.aof")));

        aof.feedAll(List.of(argv("SET", "later", "x")));
        aof.flush(true);
        assertTrue(file("appendonly.aof.2.incr.aof").contains("later"), "이름 바뀐 파일에 계속 기록");
    }

    @Test
    @DisplayName("동기화 뒤 재작성이 예약만 된 동안 - 기록 안 함 (재작성 시작 시점 데이터에 포함)")
    void scheduledRewriteDropsCommandsUntilStarted() throws IOException {
        AppendOnlyLog aof = open(new Keyspace(), 0, 64L << 20);
        aof.suspend();
        assertEquals(null, aof.resumeAfterSync(new Keyspace(), true));
        assertTrue(aof.rewriteScheduled());
        aof.feedAll(List.of(argv("SET", "dropped", "1")));
        aof.flush(true);
        assertEquals("", file("appendonly.aof.1.incr.aof"));
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
        aof.flush(true);

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
