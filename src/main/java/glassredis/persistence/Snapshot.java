package glassredis.persistence;

import glassredis.observe.Event;
import glassredis.observe.EventBus;
import glassredis.observe.RdbSnapshot;
import glassredis.store.Keyspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * RDB 스냅샷 저장·복구 - rdb.c rdbSave, rdbSaveBackground, rdbLoad
 * 실행 스레드 전용 - 파일 쓰기만 별도 스레드 (fork된 자식 역할)
 * SAVE는 직렬화부터 디스크 확정까지 실행 스레드에서, 그동안 명령 대기 (Redis와 동일)
 * Redis와 차이 - BGSAVE 직렬화도 fork 대신 실행 스레드에서, 그동안 명령 대기 (AOF 재작성과 같은 방식)
 */
public final class Snapshot {

    /** CONFIG_BGSAVE_RETRY_DELAY - 실패 후 자동 저장 재시도 간격 (초) */
    static final long RETRY_DELAY_SECONDS = 5;

    /** save 규칙 하나 - seconds 초가 지났고 그사이 changes 번 이상 바뀌면 BGSAVE */
    public record SaveParam(long seconds, long changes) {
    }

    /**
     * @param dir                     작업 폴더 (dir 설정)
     * @param filename                dbfilename - 기본 dump.rdb
     * @param saveParams              save - 비어 있으면 자동 저장·종료 시 저장 없음
     * @param stopWritesOnBgsaveError stop-writes-on-bgsave-error - BGSAVE 실패 시 쓰기 명령 거부
     */
    public record Config(Path dir, String filename, List<SaveParam> saveParams, boolean stopWritesOnBgsaveError) {

        /** save 기본값 - 3600초 1번, 300초 100번, 60초 10000번 */
        public static final List<SaveParam> DEFAULT_SAVE = List.of(
                new SaveParam(3600, 1), new SaveParam(300, 100), new SaveParam(60, 10000));

        public Config {
            saveParams = List.copyOf(saveParams);
        }

        public Config(Path dir, String filename) {
            this(dir, filename, DEFAULT_SAVE, true);
        }

        public static Config defaults(Path dir) {
            return new Config(dir, "dump.rdb");
        }

        /**
         * save 설정 문자열 - "초 횟수" 쌍 나열, 빈 문자열이면 끔
         *
         * @throws IllegalArgumentException 쌍이 안 맞거나 음수
         */
        public static List<SaveParam> parseSave(String text) {
            String trimmed = text.trim();
            if (trimmed.isEmpty()) {
                return List.of();
            }
            String[] parts = trimmed.split("\\s+");
            if (parts.length % 2 != 0) {
                throw new IllegalArgumentException("save 에는 '초 횟수' 쌍이 필요합니다: " + text);
            }
            List<SaveParam> params = new ArrayList<>();
            for (int i = 0; i < parts.length; i += 2) {
                long seconds = Long.parseLong(parts[i]);
                long changes = Long.parseLong(parts[i + 1]);
                if (seconds < 1 || changes < 0) {
                    throw new IllegalArgumentException("save 값이 잘못됐습니다: " + text);
                }
                params.add(new SaveParam(seconds, changes));
            }
            return params;
        }
    }

    /**
     * 진행 중 BGSAVE - 파일 확정까지 끝나면 future 완료
     *
     * @param written 완료 값은 쓰기에 걸린 ns
     */
    private record Bgsave(CompletableFuture<Long> written, long startNanos, String trigger, int bytes) {
    }

    private final Config config;
    private final Path file;
    private final Path temp;
    private final EventBus events;
    /** 초 단위 현재 시각 - 테스트에서 교체 */
    private final LongSupplier clock;

    /** 파일 쓰기 - fork된 자식 프로세스 역할 */
    private final ExecutorService saveThread = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("glass-redis-rdb-bgsave").daemon(true).factory());

    /** server.dirty - 마지막 저장 뒤 변경 횟수 */
    private long dirty;
    /** server.dirty_before_bgsave - BGSAVE 시작 시점의 dirty */
    private long dirtyBeforeBgsave;
    /** server.lastsave - 기동 시각은 저장된 것으로 간주 */
    private long lastSave;
    /** server.lastbgsave_try */
    private long lastBgsaveTry;
    /** server.lastbgsave_status */
    private boolean lastBgsaveOk = true;
    /** server.rdb_bgsave_scheduled - AOF 재작성 끝나면 시작 */
    private boolean bgsaveScheduled;
    /** stat_rdb_saves */
    private long saves;

    private Bgsave bgsave;

    private RdbLoader.Result loadResult;

    public Snapshot(Config config) {
        this(config, EventBus.NONE);
    }

    public Snapshot(Config config, EventBus events) {
        this(config, events, () -> System.currentTimeMillis() / 1000);
    }

    Snapshot(Config config, LongSupplier clock) {
        this(config, EventBus.NONE, clock);
    }

    Snapshot(Config config, EventBus events, LongSupplier clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.file = config.dir().resolve(config.filename());
        this.temp = config.dir().resolve("temp-" + ProcessHandle.current().pid() + ".rdb");
        this.events = Objects.requireNonNull(events, "events");
        this.clock = clock;
        this.lastSave = clock.getAsLong();
    }

    public Config config() {
        return config;
    }

    public Path file() {
        return file;
    }

    /** 기동 시 복구 - AOF 꺼져 있을 때만 (loadDataFromDisk) */
    public RdbLoader.Result load(Keyspace keyspace) {
        loadResult = RdbLoader.load(file, keyspace);
        return loadResult;
    }

    /** 기동 시 읽지 않았으면 null */
    public RdbLoader.Result loadResult() {
        return loadResult;
    }

    /** call() 뒤 server.dirty 누적 */
    public void addDirty(long count) {
        dirty += count;
    }

    public long dirty() {
        return dirty;
    }

    /** LASTSAVE - 마지막 저장 성공 시각 (초) */
    public long lastSave() {
        return lastSave;
    }

    public boolean saving() {
        return bgsave != null;
    }

    public long saves() {
        return saves;
    }

    /**
     * SAVE - 임시 파일에 쓰고 fsync 후 이름 바꾸기 (rdbSave)
     * 실패 시 임시 파일 삭제, 기존 dump.rdb 유지
     *
     * @param trigger 화면 표시용 - SAVE, 종료
     * @return 실패 사유 - 성공이면 null
     */
    public String save(Keyspace keyspace, String trigger) {
        saves++;
        long start = System.nanoTime();
        byte[] content = serialize(keyspace);
        long serializeNanos = System.nanoTime() - start;
        publishStarted(trigger, false, keyspace.size(), serializeNanos, content.length);
        String failure = writeFile(content);
        if (failure == null) {
            long saved = dirty;
            dirty = 0;
            lastSave = clock.getAsLong();
            lastBgsaveOk = true;
            long total = System.nanoTime() - start;
            publishFinished(trigger, false, content.length, total, total - serializeNanos, saved);
        } else {
            publishFailed(trigger, false, failure);
        }
        return failure;
    }

    /**
     * BGSAVE·자동 저장 시작 - rdbSaveBackground
     * 다른 백그라운드 작업 확인은 호출하는 쪽 몫 (hasActiveChildProcess)
     * 완료 처리는 checkDone
     *
     * @param trigger 화면 표시용 - BGSAVE, 예약된 BGSAVE, save 규칙
     */
    public void saveInBackground(Keyspace keyspace, String trigger) {
        saves++;
        dirtyBeforeBgsave = dirty;
        lastBgsaveTry = clock.getAsLong();
        bgsaveScheduled = false;
        long start = System.nanoTime();
        byte[] content = serialize(keyspace);
        publishStarted(trigger, true, keyspace.size(), System.nanoTime() - start, content.length);
        CompletableFuture<Long> written = CompletableFuture.supplyAsync(() -> {
            long writeStart = System.nanoTime();
            String failure = writeFile(content);
            if (failure != null) {
                throw new IllegalStateException(failure);
            }
            return System.nanoTime() - writeStart;
        }, saveThread);
        bgsave = new Bgsave(written, start, trigger, content.length);
    }

    /**
     * serverCron - BGSAVE 끝났으면 결과 반영 (backgroundSaveDoneHandlerDisk)
     *
     * @return 이번에 끝났으면 true
     */
    public boolean checkDone() {
        if (bgsave == null || !bgsave.written().isDone()) {
            return false;
        }
        Bgsave done = bgsave;
        bgsave = null;
        long total = System.nanoTime() - done.startNanos();
        if (done.written().isCompletedExceptionally()) {
            lastBgsaveOk = false;
            String reason = done.written().handle((ok, e) -> e.getCause() == null
                    ? e.getMessage() : e.getCause().getMessage()).join();
            System.err.println("[glass-redis] 백그라운드 RDB 저장 실패: " + reason);
            publishFailed(done.trigger(), true, reason);
        } else {
            // 저장하는 동안 들어온 변경은 남김
            dirty -= dirtyBeforeBgsave;
            lastSave = clock.getAsLong();
            lastBgsaveOk = true;
            System.out.printf("[glass-redis] 백그라운드 RDB 저장 완료 (%dms)%n", total / 1_000_000);
            publishFinished(done.trigger(), true, done.bytes(), total, done.written().join(), dirtyBeforeBgsave);
        }
        return true;
    }

    private void publishStarted(String trigger, boolean background, int keys, long serializeNanos, int bytes) {
        if (events.enabled()) {
            events.publish(new Event.RdbSaveStarted(trigger, background, keys, dirty, serializeNanos, bytes));
        }
    }

    private void publishFinished(String trigger, boolean background, int bytes, long totalNanos, long writeNanos,
                                 long saved) {
        if (events.enabled()) {
            events.publish(new Event.RdbSaveFinished(trigger, background, bytes, totalNanos, writeNanos, saved,
                    dirty));
        }
    }

    private void publishFailed(String trigger, boolean background, String reason) {
        if (events.enabled()) {
            events.publish(new Event.RdbSaveFailed(trigger, background, reason));
        }
    }

    /**
     * 대시보드용 - 실행 스레드에서
     *
     * @param otherChild AOF 재작성 진행 중
     */
    public RdbSnapshot view(boolean otherChild) {
        long now = clock.getAsLong();
        long fileBytes;
        try {
            // 같은 이름의 폴더 - 파일 없음으로
            fileBytes = Files.isRegularFile(file) ? Files.size(file) : -1;
        } catch (IOException e) {
            fileBytes = -1;
        }
        List<RdbSnapshot.Rule> rules = config.saveParams().stream()
                .map(p -> new RdbSnapshot.Rule(p.seconds(), p.changes()))
                .toList();
        // retryAllowed 의 경계 - now - try > 5 가 되는 첫 초
        long retryIn = lastBgsaveOk ? 0 : Math.max(0, lastBgsaveTry + RETRY_DELAY_SECONDS + 1 - now);
        RdbLoader.Result loaded = loadResult;
        return new RdbSnapshot(config.filename(), fileBytes, rules, dirty, lastSave, now - lastSave,
                bgsave != null, bgsave == null ? null : bgsave.trigger(), bgsave == null ? 0 : dirtyBeforeBgsave,
                bgsaveScheduled, lastBgsaveOk, retryIn, deniesWrites(), otherChild, saves,
                loaded == null ? "AOF" : loaded.status().name(), loaded == null ? 0 : loaded.keys(),
                loaded == null ? 0 : loaded.expired(), loaded == null ? 0 : loaded.bytes(),
                loaded == null ? 0 : loaded.elapsedMillis());
    }

    /** serverCron - 지금 맞는 save 규칙, 없으면 null */
    public SaveParam dueSaveParam() {
        long now = clock.getAsLong();
        for (SaveParam param : config.saveParams()) {
            if (dirty >= param.changes() && now - lastSave > param.seconds() && retryAllowed(now)) {
                return param;
            }
        }
        return null;
    }

    /** BGSAVE SCHEDULE - AOF 재작성 끝나면 시작 */
    public void scheduleBgsave() {
        bgsaveScheduled = true;
    }

    /** 예약된 BGSAVE 를 지금 시작해도 되는지 - 실패 직후면 재시도 간격 대기 */
    public boolean scheduledBgsaveDue() {
        return bgsaveScheduled && retryAllowed(clock.getAsLong());
    }

    private boolean retryAllowed(long now) {
        return lastBgsaveOk || now - lastBgsaveTry > RETRY_DELAY_SECONDS;
    }

    /** writeCommandsDeniedByDiskError 의 RDB 쪽 */
    public boolean deniesWrites() {
        return config.stopWritesOnBgsaveError() && !config.saveParams().isEmpty() && !lastBgsaveOk;
    }

    /**
     * 종료 - 진행 중 BGSAVE 정리 후 save 규칙 있으면 마지막 저장 (prepareForShutdown)
     *
     * @return 실패 사유 - 성공이거나 저장 안 하면 null
     */
    public String shutdown(Keyspace keyspace) {
        close();
        if (config.saveParams().isEmpty()) {
            return null;
        }
        return save(keyspace, "종료");
    }

    /**
     * 저장 없이 정리 - 진행 중 BGSAVE 는 끝날 때까지 대기
     * Redis는 자식을 죽이고 임시 파일 삭제 - 여기선 SAVE 와 같은 임시 파일을 쓰므로 대기
     */
    public void close() {
        if (bgsave != null) {
            try {
                bgsave.written().get(30, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // 결과 무관 - 종료 중
            }
            bgsave = null;
        }
        saveThread.shutdown();
    }

    private byte[] serialize(Keyspace keyspace) {
        Runtime runtime = Runtime.getRuntime();
        return RdbWriter.write(keyspace, clock.getAsLong(), runtime.totalMemory() - runtime.freeMemory(), false);
    }

    /** 임시 파일 → fsync → 이름 바꾸기, 실패 시 임시 파일 삭제 */
    private String writeFile(byte[] content) {
        try {
            Files.createDirectories(config.dir());
            Files.write(temp, content);
            Fsync.file(temp);
            Fsync.replace(temp, file);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // 다음 저장이 덮어씀
            }
            return "Write error while saving DB to the disk: " + e.getMessage();
        }
        return null;
    }
}
