package glassredis.persistence;

import glassredis.observe.Event;
import glassredis.observe.RdbSnapshot;
import glassredis.store.Entry;
import glassredis.store.Key;
import glassredis.store.Keyspace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** BGSAVE·save 규칙·재시도 간격 - 시계를 바꿔 끼워 확인 */
@Timeout(20)
class SnapshotTest {

    @TempDir
    Path dir;

    /** 초 단위 가짜 시계 */
    private final AtomicLong now = new AtomicLong(1_700_000_000L);

    private final List<Snapshot> opened = new ArrayList<>();

    @AfterEach
    void closeAll() {
        opened.forEach(Snapshot::close);
    }

    private Snapshot open(String filename, boolean stopWrites) {
        Snapshot snapshot = new Snapshot(new Snapshot.Config(dir, filename, Snapshot.Config.DEFAULT_SAVE, stopWrites),
                now::get);
        opened.add(snapshot);
        return snapshot;
    }

    private static Keyspace keyspace(String key, String value) {
        Keyspace keyspace = new Keyspace();
        keyspace.put(Key.of(key), Entry.of(value.getBytes(StandardCharsets.UTF_8)));
        return keyspace;
    }

    private static void awaitDone(Snapshot snapshot) throws InterruptedException {
        while (!snapshot.checkDone()) {
            Thread.sleep(5);
        }
    }

    @Test
    @DisplayName("save 설정 - '초 횟수' 쌍, 빈 문자열은 끔")
    void parseSave() {
        assertEquals(Snapshot.Config.DEFAULT_SAVE, Snapshot.Config.parseSave("3600 1 300 100 60 10000"));
        assertEquals(List.of(), Snapshot.Config.parseSave(""));
        assertEquals(List.of(new Snapshot.SaveParam(10, 2)), Snapshot.Config.parseSave("  10   2 "));
        assertThrows(IllegalArgumentException.class, () -> Snapshot.Config.parseSave("3600"));
        assertThrows(IllegalArgumentException.class, () -> Snapshot.Config.parseSave("0 1"));
    }

    @Test
    @DisplayName("save 규칙 - 시간이 '넘게' 지나고 변경 횟수가 채워져야")
    void dueSaveParam() {
        Snapshot snapshot = open("dump.rdb", true);
        now.addAndGet(3600);
        snapshot.addDirty(1);
        assertNull(snapshot.dueSaveParam(), "3600초 정각은 아직 (lastsave 와의 차이 > seconds)");
        now.addAndGet(1);
        assertEquals(new Snapshot.SaveParam(3600, 1), snapshot.dueSaveParam());

        Snapshot busy = open("busy.rdb", true);
        now.addAndGet(61);
        busy.addDirty(9_999);
        assertNull(busy.dueSaveParam());
        busy.addDirty(1);
        assertEquals(new Snapshot.SaveParam(60, 10_000), busy.dueSaveParam());
    }

    @Test
    @DisplayName("BGSAVE - 저장 중 들어온 변경은 dirty 에 남고, lastsave 갱신")
    void backgroundSave() throws Exception {
        Snapshot snapshot = open("dump.rdb", true);
        Keyspace keyspace = keyspace("k", "v");
        snapshot.addDirty(5);
        now.addAndGet(10);

        snapshot.saveInBackground(keyspace, "BGSAVE");
        assertTrue(snapshot.saving());
        snapshot.addDirty(2);
        awaitDone(snapshot);

        assertFalse(snapshot.saving());
        assertEquals(2, snapshot.dirty(), "dirty - dirty_before_bgsave");
        assertEquals(now.get(), snapshot.lastSave());
        assertTrue(Files.size(dir.resolve("dump.rdb")) > 0);

        Keyspace loaded = new Keyspace();
        assertEquals(RdbLoader.Status.OK, RdbLoader.load(dir.resolve("dump.rdb"), loaded).status());
        assertEquals(1, loaded.size());
    }

    @Test
    @DisplayName("BGSAVE 실패 - 쓰기 거부, 5초 지나야 자동 저장 재시도, SAVE 성공하면 풀림")
    void failedBackgroundSave() throws Exception {
        // 같은 이름의 비어 있지 않은 폴더 - 이름 바꾸기 실패
        Files.createDirectories(dir.resolve("blocked.rdb").resolve("inside"));
        Snapshot snapshot = open("blocked.rdb", true);
        Keyspace keyspace = keyspace("k", "v");
        now.addAndGet(3601);
        snapshot.addDirty(1);

        snapshot.saveInBackground(keyspace, "BGSAVE");
        awaitDone(snapshot);
        assertTrue(snapshot.deniesWrites());
        assertEquals(1, snapshot.dirty(), "실패하면 dirty 그대로");
        try (var files = Files.list(dir)) {
            assertFalse(files.anyMatch(p -> p.getFileName().toString().startsWith("temp-")), "임시 파일 삭제");
        }

        now.addAndGet(5);
        assertNull(snapshot.dueSaveParam(), "실패 후 5초까지는 재시도 안 함");
        now.addAndGet(1);
        assertEquals(new Snapshot.SaveParam(3600, 1), snapshot.dueSaveParam());

        Files.delete(dir.resolve("blocked.rdb").resolve("inside"));
        Files.delete(dir.resolve("blocked.rdb"));
        assertNull(snapshot.save(keyspace, "SAVE"));
        assertFalse(snapshot.deniesWrites(), "SAVE 성공도 lastbgsave_status 를 OK 로");
        assertEquals(0, snapshot.dirty());
    }

    @Test
    @DisplayName("stop-writes-on-bgsave-error no 면 실패해도 쓰기 허용")
    void stopWritesDisabled() throws Exception {
        Files.createDirectories(dir.resolve("blocked.rdb").resolve("inside"));
        Snapshot snapshot = open("blocked.rdb", false);
        snapshot.saveInBackground(keyspace("k", "v"), "BGSAVE");
        awaitDone(snapshot);
        assertFalse(snapshot.deniesWrites());
    }

    @Test
    @DisplayName("예약된 BGSAVE - 실패 직후면 재시도 간격 대기")
    void scheduledBgsave() throws Exception {
        Snapshot snapshot = open("dump.rdb", true);
        assertFalse(snapshot.scheduledBgsaveDue());
        snapshot.scheduleBgsave();
        assertTrue(snapshot.scheduledBgsaveDue());
        snapshot.saveInBackground(keyspace("k", "v"), "BGSAVE");
        assertFalse(snapshot.scheduledBgsaveDue(), "시작하면 예약 해제");
        awaitDone(snapshot);
    }

    @Test
    @DisplayName("저장 이벤트 - 시작 시 담은 변경 수, 끝날 때 저장 중 들어와 남은 변경 수")
    void publishesSaveEvents() throws Exception {
        List<Event> seen = new ArrayList<>();
        Snapshot snapshot = new Snapshot(Snapshot.Config.defaults(dir), seen::add, now::get);
        opened.add(snapshot);
        Keyspace keyspace = keyspace("k", "v");
        snapshot.addDirty(5);

        snapshot.saveInBackground(keyspace, "save 60 10000");
        snapshot.addDirty(2);
        awaitDone(snapshot);
        assertNull(snapshot.save(keyspace, "SAVE"));

        assertEquals(4, seen.size());
        Event.RdbSaveStarted started = (Event.RdbSaveStarted) seen.get(0);
        assertEquals("save 60 10000", started.trigger());
        assertTrue(started.background());
        assertEquals(1, started.keys());
        assertEquals(5, started.dirty());
        Event.RdbSaveFinished finished = (Event.RdbSaveFinished) seen.get(1);
        assertEquals(5, finished.saved());
        assertEquals(2, finished.remaining());
        assertEquals(started.bytes(), finished.bytes());
        assertEquals(Files.size(dir.resolve("dump.rdb")), finished.bytes());
        assertTrue(finished.writeNanos() > 0 && finished.writeNanos() <= finished.totalNanos());

        Event.RdbSaveFinished foreground = (Event.RdbSaveFinished) seen.get(3);
        assertFalse(foreground.background());
        assertEquals(2, foreground.saved());
        assertEquals(0, foreground.remaining());
    }

    @Test
    @DisplayName("BGSAVE 실패 이벤트 - 사유 포함, 화면 상태에 재시도까지 남은 초")
    void publishesFailure() throws Exception {
        Files.createDirectories(dir.resolve("blocked.rdb").resolve("inside"));
        List<Event> seen = new ArrayList<>();
        Snapshot snapshot = new Snapshot(new Snapshot.Config(dir, "blocked.rdb"), seen::add, now::get);
        opened.add(snapshot);

        snapshot.saveInBackground(keyspace("k", "v"), "BGSAVE");
        awaitDone(snapshot);

        Event.RdbSaveFailed failed = (Event.RdbSaveFailed) seen.get(1);
        assertEquals("BGSAVE", failed.trigger());
        assertTrue(failed.reason().startsWith("Write error while saving DB to the disk"));
        RdbSnapshot view = snapshot.view(false);
        assertFalse(view.lastBgsaveOk());
        assertTrue(view.deniesWrites());
        assertEquals(6, view.retryIn(), "now - lastbgsave_try > 5 가 되는 첫 초까지");
        now.addAndGet(6);
        assertEquals(0, snapshot.view(false).retryIn());
    }

    @Test
    @DisplayName("화면 상태 - 규칙, 지난 초, 진행 중 저장이 담은 변경 수")
    void view() throws Exception {
        Snapshot snapshot = open("dump.rdb", true);
        snapshot.addDirty(3);
        now.addAndGet(42);

        RdbSnapshot idle = snapshot.view(true);
        assertEquals(List.of(new RdbSnapshot.Rule(3600, 1), new RdbSnapshot.Rule(300, 100),
                new RdbSnapshot.Rule(60, 10000)), idle.rules());
        assertEquals(3, idle.dirty());
        assertEquals(42, idle.sinceSave());
        assertEquals(-1, idle.fileBytes());
        assertTrue(idle.otherChild());
        assertEquals("AOF", idle.loadStatus(), "기동 시 RDB 를 안 읽었으면 AOF 로 복구한 것");

        snapshot.saveInBackground(keyspace("k", "v"), "BGSAVE");
        snapshot.addDirty(1);
        RdbSnapshot saving = snapshot.view(false);
        assertTrue(saving.saving());
        assertEquals("BGSAVE", saving.savingTrigger());
        assertEquals(3, saving.savingDirty());
        assertEquals(4, saving.dirty());
        awaitDone(snapshot);

        RdbSnapshot done = snapshot.view(false);
        assertFalse(done.saving());
        assertNull(done.savingTrigger());
        assertEquals(0, done.sinceSave());
        assertEquals(Files.size(dir.resolve("dump.rdb")), done.fileBytes());
    }
}
