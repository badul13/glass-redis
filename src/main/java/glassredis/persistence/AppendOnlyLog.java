package glassredis.persistence;

import glassredis.command.CommandRegistry;
import glassredis.observe.AofSnapshot;
import glassredis.observe.Event;
import glassredis.observe.EventBus;
import glassredis.store.Keyspace;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * AOF 기록·복구·재작성 - aof.c
 * 실행 스레드 전용 - fsync와 재작성 파일 쓰기만 별도 스레드
 * 응답 전달은 flush() 뒤 - 응답 받은 쓰기는 최소 OS 캐시까지 반영
 * Redis와 차이 - 재작성 직렬화를 fork 대신 실행 스레드에서, 그동안 명령 대기
 */
public final class AppendOnlyLog implements AutoCloseable {

    /** appendfsync */
    public enum FsyncPolicy {
        /** write마다 fsync */
        ALWAYS,
        /** 1초마다 백그라운드 fsync - 기본값 */
        EVERYSEC,
        /** fsync 없음 - OS 몫 */
        NO;

        public static FsyncPolicy parse(String text) {
            return valueOf(text.toUpperCase(Locale.ROOT));
        }

        public String configName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * @param dir 작업 폴더 (dir 설정) - AOF는 그 안 appendonlydir
     * @param rewritePercentage auto-aof-rewrite-percentage - 0이면 자동 재작성 끔
     * @param rewriteMinSize auto-aof-rewrite-min-size - 이보다 작으면 자동 재작성 안 함
     * @param rdbPreamble aof-use-rdb-preamble - base 를 RDB 형식으로, 아니면 명령 나열
     */
    public record Config(Path dir, boolean enabled, FsyncPolicy fsync, int rewritePercentage, long rewriteMinSize,
                         boolean rdbPreamble) {

        /** Redis 기본값 - appendonly no, everysec, 100%, 64MB, preamble yes */
        public static Config defaults(Path dir) {
            return new Config(dir, false, FsyncPolicy.EVERYSEC, 100, 64L * 1024 * 1024, true);
        }
    }

    /** appenddirname 기본값 */
    public static final String DIRNAME = "appendonlydir";

    private static final byte[][] MULTI = {AofFormat.ascii("MULTI")};
    private static final byte[][] EXEC = {AofFormat.ascii("EXEC")};

    private final Config config;
    private final Path aofDir;
    private final EventBus events;

    private AofManifest manifest = new AofManifest();

    /** 지금 쓰는 incr 파일 - AOF 꺼져 있으면 null */
    private FileChannel channel;
    private String channelName;

    /** aof_buf - beforeSleep에서 한 번에 write */
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    /** 버퍼 속 명령 - 이벤트 표시용, 관측 꺼지면 비움 */
    private final List<List<byte[]>> bufferedCommands = new ArrayList<>();

    /** aof_selected_db - -1이면 다음 명령 앞에 SELECT */
    private int selectedDb = -1;

    /** aof_current_size */
    private long currentSize;
    /** aof_rewrite_base_size - 자동 재작성 증가율 기준 */
    private long rewriteBaseSize;
    /** aof_last_incr_size */
    private long lastIncrSize;
    /** aof_last_incr_fsync_offset - fsync를 맡긴 위치 */
    private long lastIncrFsyncOffset;
    /** aof_last_fsync - fsync를 맡긴 시각 */
    private long lastFsyncMillis;

    /** fsync 스레드가 끝낸 위치 - 화면의 "확정" 표시 */
    private final AtomicLong syncedOffset = new AtomicLong();
    private final AtomicBoolean fsyncInProgress = new AtomicBoolean();

    /** server.master_repl_offset */
    private LongSupplier replOffset = () -> 0;
    /** fsynced_reploff_pending - fsync 스레드가 씀 */
    private final AtomicLong fsyncedReploffPending = new AtomicLong();
    /** fsynced_reploff - AOF 켜져 있으면 0 부터, 꺼져 있으면 -1 */
    private long fsyncedReploff;
    /** AOF_WAIT_REWRITE - 동기화 뒤 첫 재작성 대기 */
    private boolean waitRewrite;
    /** AOF_OFF 상태 - 레플리카 전체 동기화 중 */
    private boolean suspended;
    /** aof_last_fsync - 초 단위, everysec 판정 */
    private long lastFsyncSecond;
    /** aof_flush_postponed_start - fsync 진행 중이라 쓰기를 미룬 시각 (초), 0 이면 안 미룸 */
    private long flushPostponedStart;
    /** aof_delayed_fsync - 2초 넘게 밀려 fsync 를 안 기다리고 쓴 횟수 */
    private long delayedFsync;
    /** aof_last_write_status·errno - null 이면 C_OK, 아니면 실패 사유 */
    private String lastWriteError;
    /** 쓰기 오류 재시도 시각 (밀리초) - cron 1초 주기 */
    private long lastWriteRetryMillis;
    /** stat_aofrw_consecutive_failures - 성공하면 0 */
    private int consecutiveRewriteFailures;
    /** aofRewriteLimited 의 static 상태 - 다음 대기 분, 다음 허용 시각 (초) */
    private int nextRewriteDelayMinutes;
    private long nextRewriteTime;
    private static final int REWRITE_LIMIT_THRESHOLD = 3;
    private static final int REWRITE_LIMIT_MAX_MINUTES = 60;
    /** getTempIncrAofName - 동기화 뒤 첫 재작성 동안 쓰는 incr, manifest 에 없음 */
    static final String TEMP_INCR_NAME = "temp-" + AofManifest.FILENAME + ".incr";
    /** 쓰기 오류 로그 시각 (초) - AOF_WRITE_LOG_ERROR_RATE */
    private long lastWriteErrorLog;
    private static final long WRITE_ERROR_LOG_RATE = 30;
    /** aof_bio_fsync_status·errno - fsync 스레드가 씀, null 이면 C_OK */
    private final AtomicReference<String> bioFsyncError = new AtomicReference<>();

    /** bio 스레드 - fsync와 옛 파일 닫기 */
    private final ExecutorService fsyncThread = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("glass-redis-aof-fsync").daemon(true).factory());
    /** 재작성 결과 파일 쓰기 - fork된 자식 프로세스 역할 */
    private final ExecutorService rewriteThread = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("glass-redis-aof-rewrite").daemon(true).factory());

    private Rewrite rewrite;
    private int rewrites;
    /** aof_rewrite_scheduled */
    private boolean rewriteScheduled;

    private AofLoader.Result loadResult;

    /** 진행 중 재작성 - 임시 파일에 쓰기 끝나면 future 완료 */
    private record Rewrite(Path temp, CompletableFuture<Void> written, long startNanos, long sizeBefore) {
    }

    public AppendOnlyLog(Config config, EventBus events) {
        this.config = Objects.requireNonNull(config, "config");
        this.aofDir = config.dir().resolve(DIRNAME);
        this.events = Objects.requireNonNull(events, "events");
        this.fsyncedReploff = config.enabled() ? 0 : -1;
    }

    public boolean enabled() {
        return config.enabled();
    }

    public Config config() {
        return config;
    }

    public Path directory() {
        return aofDir;
    }

    /**
     * 기동 - manifest 읽기, 켜져 있으면 복구 후 이어 쓸 파일 열기
     * 실행 스레드 시작 전 호출 - loadDataFromDisk, aofOpenIfNeededOnServerStart
     *
     * @throws IOException 복구 실패 - 기동 중단
     */
    public void start(Keyspace keyspace, CommandRegistry registry) throws IOException {
        start(keyspace, registry, () -> {
        });
    }

    /** @param progress 로딩 중 주기적으로 - processEventsWhileBlocked */
    public void start(Keyspace keyspace, CommandRegistry registry, Runnable progress) throws IOException {
        manifest = AofManifest.load(aofDir);
        if (!config.enabled()) {
            return;
        }
        upgradeLegacyFile();
        loadResult = new AofLoader(aofDir, registry, keyspace, progress).load(manifest);
        if (loadResult.status() == AofLoader.Status.FAILED) {
            throw new IOException("AOF 복구 실패: " + loadResult.error());
        }
        currentSize = loadResult.bytes();
        rewriteBaseSize = loadResult.baseBytes();
        openOnStart(keyspace);
    }

    /**
     * loadAppendOnlyFiles 앞부분 + aofUpgradePrepare - Redis 7.0 전 방식의 dir/appendonly.aof 가 있으면
     * 폴더가 없거나 manifest 가 비었거나, 옮기다 멈춘 흔적(base 만 그 이름인데 폴더에 없음)일 때
     * manifest 에 seq 1 base 로 적고 파일을 appendonlydir 로 옮김
     */
    private void upgradeLegacyFile() throws IOException {
        Path legacy = config.dir().resolve(AofManifest.FILENAME);
        if (!Files.isRegularFile(legacy)) {
            return;
        }
        AofManifest.FileInfo base = manifest.base();
        boolean interrupted = base != null && manifest.incr().isEmpty() && base.name().equals(AofManifest.FILENAME)
                && !Files.exists(aofDir.resolve(AofManifest.FILENAME));
        if (Files.isDirectory(aofDir) && !manifest.isEmpty() && !interrupted) {
            return;
        }
        Files.createDirectories(aofDir);
        AofManifest next = new AofManifest();
        next.useLegacyFileAsBase();
        next.persist(aofDir);
        Files.move(legacy, aofDir.resolve(AofManifest.FILENAME));
        manifest = next;
        System.out.printf("[glass-redis] Successfully migrated an old-style AOF file (%s) into the AOF directory (%s).%n",
                AofManifest.FILENAME, DIRNAME);
    }

    public AofLoader.Result loadResult() {
        return loadResult;
    }

    /** 데이터 없이 처음 켜지면 빈 base부터 - 이후 파일 구성이 항상 base + incr */
    private void openOnStart(Keyspace keyspace) throws IOException {
        Files.createDirectories(aofDir);
        if (manifest.isEmpty()) {
            String base = manifest.newBase(config.rdbPreamble());
            writeBase(aofDir.resolve(base), baseContent(keyspace));
        }
        channelName = manifest.lastIncr();
        Path path = aofDir.resolve(channelName);
        channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND);
        manifest.persist(aofDir);
        lastIncrSize = channel.size();
        lastIncrFsyncOffset = lastIncrSize;
        syncedOffset.set(lastIncrSize);
    }

    /** rewriteAppendOnlyFile 의 본문 - preamble 이면 RDB (aof-base 1), 아니면 명령 나열 */
    private byte[] baseContent(Keyspace keyspace) {
        return config.rdbPreamble() ? rdbBody(keyspace).finish() : AofRewriter.rewrite(keyspace);
    }

    /** preamble 용 RDB 본문 - aof-base 1, EOF·CRC64 는 finish() */
    private static RdbWriter.Body rdbBody(Keyspace keyspace) {
        Runtime runtime = Runtime.getRuntime();
        return RdbWriter.serialize(keyspace, System.currentTimeMillis() / 1000,
                runtime.totalMemory() - runtime.freeMemory(), true);
    }

    /** rewriteAppendOnlyFile - 임시 이름으로 쓰고 fsync 후 교체 */
    private static void writeBase(Path target, byte[] content) throws IOException {
        Path temp = target.resolveSibling("temp-rewriteaof-" + ProcessHandle.current().pid() + ".aof");
        Files.write(temp, content);
        Fsync.file(temp);
        Fsync.replace(temp, target);
    }

    /**
     * 명령 하나 버퍼에 - feedAppendOnlyFile
     * DB가 바뀌면 SELECT 먼저, MULTI·EXEC는 SELECT 없이
     */
    public void feed(List<byte[]> argv) {
        if (!accepting()) {
            return;
        }
        if (selectedDb != 0) {
            List<byte[]> select = List.of(AofFormat.ascii("SELECT"), AofFormat.ascii("0"));
            AofFormat.command(buffer, select);
            remember(select);
            selectedDb = 0;
        }
        AofFormat.command(buffer, argv);
        remember(argv);
    }

    /**
     * propagateNow 의 AOF 쪽 - feedAppendOnlyFile(dbid, ...)
     *
     * @param dbid -1 이면 SELECT 없이 (MULTI·EXEC)
     */
    public void feed(int dbid, List<byte[]> argv) {
        if (!accepting()) {
            return;
        }
        if (dbid < 0) {
            feedWithoutSelect(argv);
        } else {
            feed(argv);
        }
    }

    /** 명령 하나가 여러 전파를 낳으면 트랜잭션으로 - propagatePendingCommands */
    public void feedAll(List<List<byte[]>> commands) {
        if (!accepting() || commands.isEmpty()) {
            return;
        }
        boolean transaction = commands.size() > 1;
        if (transaction) {
            feedWithoutSelect(List.of(MULTI));
        }
        commands.forEach(this::feed);
        if (transaction) {
            feedWithoutSelect(List.of(EXEC));
        }
    }

    private void feedWithoutSelect(List<byte[]> argv) {
        AofFormat.command(buffer, argv);
        remember(argv);
    }

    private void remember(List<byte[]> argv) {
        if (events.enabled()) {
            bufferedCommands.add(argv);
        }
    }

    /** beforeSleep - flushAppendOnlyFile(0) */
    public void flush() {
        flush(false);
    }

    /** serverCron - fsync 가 늦어 미룬 쓰기가 있으면 다시 시도 */
    public void cronFlush() {
        if (flushPostponedStart != 0) {
            flush(false);
        }
        retryAfterWriteError();
    }

    /**
     * 버퍼를 OS로 write, 정책에 따라 fsync (flushAppendOnlyFile)
     * everysec 은 초가 바뀌었을 때 fsync 를 맡김 (unixtime > aof_last_fsync)
     * fsync 가 아직 진행 중이면 write 자체를 최대 2초 미룸 - force 면 바로
     * 쓰기 실패 시 버퍼 유지 후 다음 번에 재시도
     */
    public void flush(boolean force) {
        if (channel == null) {
            return;
        }
        long now = System.currentTimeMillis() / 1000;
        boolean syncInProgress = false;
        if (buffer.size() == 0) {
            // 쓸 것이 없어도 확정 안 된 바이트가 있으면 초가 바뀔 때 fsync
            if (config.fsync() == FsyncPolicy.EVERYSEC && lastIncrFsyncOffset != lastIncrSize
                    && now > lastFsyncSecond && !fsyncInProgress.get()) {
                tryFsync(now, false);
            }
            return;
        }
        if (config.fsync() == FsyncPolicy.EVERYSEC) {
            syncInProgress = fsyncInProgress.get();
        }
        if (config.fsync() == FsyncPolicy.EVERYSEC && !force && syncInProgress) {
            if (flushPostponedStart == 0) {
                flushPostponedStart = now;
                return;
            } else if (now - flushPostponedStart < 2) {
                return;
            }
            // 2초 넘게 밀림 - fsync 를 기다리지 않고 씀 (aof_delayed_fsync)
            delayedFsync++;
        }
        byte[] bytes = buffer.toByteArray();
        ByteBuffer out = ByteBuffer.wrap(bytes);
        IOException failure = null;
        try {
            while (out.hasRemaining()) {
                channel.write(out);
            }
        } catch (IOException e) {
            failure = e;
        }
        // 쓰기 시도함 - 미룸 표시 해제 (실패여도)
        flushPostponedStart = 0;
        if (failure != null) {
            handleWriteError(bytes, out.position(), failure, now);
            return;
        }
        if (lastWriteError != null) {
            System.err.println("[glass-redis] AOF 쓰기 오류 해소, 쓰기 명령 다시 받음");
            lastWriteError = null;
        }
        buffer.reset();
        currentSize += bytes.length;
        lastIncrSize += bytes.length;
        if (events.enabled()) {
            events.publish(Event.aofWritten(bufferedCommands, bytes.length, channelName, lastIncrSize, currentSize));
        }
        bufferedCommands.clear();
        tryFsync(now, syncInProgress);
    }

    /**
     * 쓰기 실패 - flushAppendOnlyFile 의 nwritten != len 분기
     * 일부만 쓰였으면 이번 쓰기 전 길이로 truncate - 성공 시 안 쓴 것으로(-1), 실패 시 쓴 만큼 버퍼에서 제거
     * always - 응답이 이미 나갈 상태라 되돌릴 수 없음, 종료
     * 그 밖 - 버퍼 유지, aof_last_write_status = C_ERR → 쓰기 명령 MISCONF, cron 1초마다 재시도
     */
    private void handleWriteError(byte[] bytes, int written, IOException e, long now) {
        boolean canLog = now - lastWriteErrorLog > WRITE_ERROR_LOG_RATE;
        if (canLog) {
            lastWriteErrorLog = now;
        }
        String reason;
        if (written == 0) {
            // 메시지 없는 예외(ClosedChannelException 등)도 오류 상태로 - null 이면 MISCONF 안 걸림
            reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            if (canLog) {
                System.err.println("[glass-redis] AOF 쓰기 실패: " + reason);
            }
        } else {
            if (canLog) {
                System.err.printf("[glass-redis] AOF 일부만 기록 (nwritten=%d, expected=%d)%n", written, bytes.length);
            }
            try {
                channel.truncate(lastIncrSize);
                written = 0;
            } catch (IOException truncateError) {
                if (canLog) {
                    System.err.println("[glass-redis] AOF 일부 기록 제거 실패 - 다음 기동 시 복구 거부 가능: "
                            + truncateError.getMessage());
                }
            }
            // short write - Redis 는 ENOSPC 로 기록
            reason = "No space left on device";
        }
        if (config.fsync() == FsyncPolicy.ALWAYS) {
            throw new IllegalStateException("AOF 쓰기 실패 - appendfsync always 에서 복구 불가, 종료", e);
        }
        lastWriteError = reason;
        if (written > 0) {
            currentSize += written;
            lastIncrSize += written;
            buffer.reset();
            buffer.write(bytes, written, bytes.length - written);
        }
    }

    /** 테스트용 - 쓰기 실패 흉내 채널로 교체, 이전 채널 반환 */
    FileChannel swapChannel(FileChannel replacement) {
        FileChannel previous = channel;
        channel = replacement;
        return previous;
    }

    /** 테스트용 - cron 재시도 주기 무시하고 즉시 재시도 */
    void retryNowForTest() {
        lastWriteRetryMillis = 0;
        retryAfterWriteError();
    }

    /** serverCron run_with_period(1000) - 쓰기 오류 상태면 1초마다 다시 씀 */
    private void retryAfterWriteError() {
        long nowMillis = System.currentTimeMillis();
        if (lastWriteError != null && nowMillis - lastWriteRetryMillis >= 1000) {
            lastWriteRetryMillis = nowMillis;
            flush(false);
        }
    }

    /**
     * 쓰기 명령 거부 사유 - writeCommandsDeniedByDiskError 의 AOF 쪽, 문제 없으면 null
     * AOF_OFF(꺼짐·전체 동기화 중)면 검사 안 함, write 실패 다음으로 bio fsync 실패
     */
    public String writeError() {
        if (!config.enabled() || suspended) {
            return null;
        }
        return lastWriteError != null ? lastWriteError : bioFsyncError.get();
    }

    /** try_fsync - always 는 바로, everysec 은 초가 바뀌었고 진행 중인 fsync 가 없을 때 맡김 */
    private void tryFsync(long now, boolean syncInProgress) {
        switch (config.fsync()) {
            case ALWAYS -> {
                long start = System.nanoTime();
                try {
                    channel.force(false);
                } catch (IOException e) {
                    // always에서 fsync 실패 시 종료 - aof.c 동일 처리
                    throw new IllegalStateException("AOF fsync 실패", e);
                }
                lastIncrFsyncOffset = lastIncrSize;
                syncedOffset.set(lastIncrSize);
                lastFsyncSecond = now;
                lastFsyncMillis = System.currentTimeMillis();
                fsyncedReploffPending.set(replOffset.getAsLong());
                publishFsync(channelName, lastIncrSize, System.nanoTime() - start);
            }
            case EVERYSEC -> {
                if (now > lastFsyncSecond) {
                    if (!syncInProgress) {
                        scheduleFsync();
                    }
                    lastFsyncSecond = now;
                }
            }
            case NO -> {
                // OS가 알아서 디스크로
            }
        }
    }

    /** server.master_repl_offset - fsync 된 복제 위치 기록용 */
    public void replOffset(LongSupplier supplier) {
        replOffset = supplier;
    }

    /**
     * beforeSleep - fsync 스레드가 남긴 위치를 확정 (fsynced_reploff)
     * 동기화 뒤 첫 재작성 중이면 아직 디스크 보장이 없어 그대로
     */
    public void publishFsyncedReploff() {
        if (config.enabled() && fsyncedReploff != -1 && !waitRewrite) {
            fsyncedReploff = fsyncedReploffPending.get();
        }
    }

    /** REPLCONF ACK 의 FACK - AOF 꺼져 있으면 -1 (보내지 않음) */
    public long fsyncedReploff() {
        return fsyncedReploff;
    }

    /**
     * stopAppendOnly - 레플리카 전체 동기화 동안 기록 멈춤
     * 버퍼는 지금 쓰고 fsync 후 파일 닫음 (aof_fd = -1) - 이후 옛 incr 에 덧붙는 일 없음
     * 진행 중 재작성은 버림 (killAppendOnlyChild) - 옛 데이터 기준이라
     */
    public void suspend() {
        if (!config.enabled()) {
            return;
        }
        flush(true);
        if (channel != null) {
            try {
                channel.force(false);
                lastFsyncSecond = System.currentTimeMillis() / 1000;
            } catch (IOException e) {
                System.err.println("[glass-redis] AOF fsync 실패: " + e.getMessage());
            }
            try {
                channel.close();
            } catch (IOException e) {
                System.err.println("[glass-redis] AOF 닫기 실패: " + e.getMessage());
            }
            channel = null;
        }
        suspended = true;
        selectedDb = -1;
        rewriteScheduled = false;
        lastIncrSize = 0;
        lastIncrFsyncOffset = 0;
        // AOF_OFF - ACK 에 FACK 안 붙음
        fsyncedReploff = -1;
        fsyncedReploffPending.set(0);
        killRewrite();
        buffer.reset();
        bufferedCommands.clear();
    }

    /** AOF_WAIT_REWRITE - 동기화 뒤 첫 재작성이 끝나기 전이라 AOF 가 아직 완전하지 않음 */
    public boolean waitingInitialRewrite() {
        return config.enabled() && !suspended && waitRewrite;
    }

    /** finishShutdown - flushAppendOnlyFile(1) 후 fsync, 실패는 기록만 */
    public void flushAndSync() {
        if (!config.enabled() || suspended) {
            return;
        }
        flush(true);
        if (channel != null) {
            try {
                channel.force(false);
            } catch (IOException e) {
                System.err.println("[glass-redis] Fail to fsync the AOF file: " + e.getMessage());
            }
        }
    }

    /** killAppendOnlyChild - 쓰던 임시 base 는 쓰기 끝난 뒤 삭제 */
    public void killRewrite() {
        Rewrite killed = rewrite;
        if (killed == null) {
            return;
        }
        rewrite = null;
        killed.written().whenComplete((ok, e) -> {
            try {
                Files.deleteIfExists(killed.temp());
            } catch (IOException ignored) {
                // 다음 재작성이 덮어씀
            }
        });
    }

    /**
     * feedAppendOnlyFile 의 기록 조건 - AOF_ON, 또는 AOF_WAIT_REWRITE 이면서 재작성 진행 중
     * 재작성 예약만 된 동안은 버림 - 재작성 시작 시점의 데이터에 포함
     */
    private boolean accepting() {
        return config.enabled() && !suspended && (!waitRewrite || rewrite != null);
    }

    public boolean suspended() {
        return suspended;
    }

    /**
     * restartAOFAfterSYNC → startAppendOnly - 새 데이터로 재작성, 끝날 때까지 FACK 는 0
     *
     * @return 재작성 시작 실패 사유, 다른 자식 작업 중이라 예약했거나 시작했으면 null
     */
    public String resumeAfterSync(Keyspace keyspace, boolean otherChildActive) {
        if (!config.enabled()) {
            return null;
        }
        suspended = false;
        selectedDb = -1;
        fsyncedReploffPending.set(replOffset.getAsLong());
        fsyncedReploff = 0;
        waitRewrite = true;
        lastFsyncSecond = System.currentTimeMillis() / 1000;
        // "AOF reopen, just ignore the last error" - 이전 쓰기·fsync 오류 상태 해제
        bioFsyncError.set(null);
        lastWriteError = null;
        if (otherChildActive) {
            rewriteScheduled = true;
            return null;
        }
        String failure = rewriteInBackground(keyspace, "레플리카 동기화");
        if (failure != null) {
            // 재작성 시작 실패 - aof_state = AOF_OFF
            suspended = true;
            waitRewrite = false;
            fsyncedReploff = -1;
        }
        return failure;
    }

    /** aof_background_fsync - 호출하는 쪽이 진행 중 아님을 확인 */
    private void scheduleFsync() {
        lastFsyncMillis = System.currentTimeMillis();
        if (!fsyncInProgress.compareAndSet(false, true)) {
            return;
        }
        FileChannel target = channel;
        String name = channelName;
        long offset = lastIncrSize;
        lastIncrFsyncOffset = offset;
        // bioCreateFsyncJob(fd, master_repl_offset) - fsync 가 끝나면 이 복제 위치까지 디스크에 있음
        long reploff = replOffset.getAsLong();
        fsyncThread.execute(() -> {
            long start = System.nanoTime();
            try {
                target.force(false);
                syncedOffset.accumulateAndGet(offset, Math::max);
                fsyncedReploffPending.set(reploff);
                publishFsync(name, offset, System.nanoTime() - start);
                bioFsyncError.set(null);
            } catch (IOException e) {
                // 처음 실패할 때만 로그 - bio.c 동일
                String reason = e.getMessage() == null ? e.toString() : e.getMessage();
                if (bioFsyncError.getAndSet(reason) == null) {
                    System.err.println("[glass-redis] AOF fsync 실패: " + reason);
                }
            } finally {
                fsyncInProgress.set(false);
            }
        });
    }

    private void publishFsync(String file, long offset, long nanos) {
        if (events.enabled()) {
            events.publish(new Event.AofFsynced(file, offset, nanos));
        }
    }

    /** serverCron 의 checkChildrenDone - 재작성 끝났으면 새 base 로 교체 */
    public void checkRewriteDone() {
        if (rewrite != null && rewrite.written().isDone()) {
            finishRewrite();
        }
    }

    /**
     * serverCron - 자동 재작성 조건 확인
     * 다른 백그라운드 작업 확인은 호출하는 쪽 몫 (hasActiveChildProcess)
     */
    public void autoRewrite(Keyspace keyspace) {
        // aof_state == AOF_ON - 동기화 중(OFF)·동기화 뒤 재작성 대기(WAIT_REWRITE)는 제외
        if (config.enabled() && !suspended && !waitRewrite && rewrite == null && config.rewritePercentage() > 0
                && currentSize > config.rewriteMinSize()) {
            long base = rewriteBaseSize > 0 ? rewriteBaseSize : 1;
            long growth = currentSize * 100 / base - 100;
            if (growth >= config.rewritePercentage() && !rewriteLimited()) {
                rewriteInBackground(keyspace, "자동 (" + growth + "% 증가)");
            }
        }
    }

    /**
     * aofRewriteLimited - 연속 3번 실패하면 1분부터 두 배씩(최대 60분) 다음 자동·예약 재작성을 늦춤
     * 부를 때마다 판정 상태가 바뀜 - Redis 처럼 다른 조건이 다 맞은 뒤 마지막에 호출
     */
    public boolean rewriteLimited() {
        if (consecutiveRewriteFailures < REWRITE_LIMIT_THRESHOLD) {
            // 제한에서 풀리는 중일 수 있음 - 상태 초기화
            nextRewriteDelayMinutes = 0;
            nextRewriteTime = 0;
            return false;
        }
        long now = System.currentTimeMillis() / 1000;
        if (nextRewriteTime != 0) {
            if (now < nextRewriteTime) {
                return true;
            }
            nextRewriteTime = 0;
            return false;
        }
        nextRewriteDelayMinutes = nextRewriteDelayMinutes == 0 ? 1 : nextRewriteDelayMinutes * 2;
        if (nextRewriteDelayMinutes > REWRITE_LIMIT_MAX_MINUTES) {
            nextRewriteDelayMinutes = REWRITE_LIMIT_MAX_MINUTES;
        }
        nextRewriteTime = now + nextRewriteDelayMinutes * 60L;
        System.err.printf("[glass-redis] AOF 재작성 연속 실패로 제한 - %d분 뒤 다시 시도%n", nextRewriteDelayMinutes);
        return true;
    }

    /** stat_aofrw_consecutive_failures */
    public int consecutiveRewriteFailures() {
        return consecutiveRewriteFailures;
    }

    public boolean rewriting() {
        return rewrite != null;
    }

    /** BGSAVE 중 BGREWRITEAOF - 끝나면 시작 (aof_rewrite_scheduled), 수동 요청이라 실패 제한 해제 */
    public void scheduleRewrite() {
        rewriteScheduled = true;
        consecutiveRewriteFailures = 0;
    }

    public boolean rewriteScheduled() {
        return rewriteScheduled;
    }

    /**
     * BGREWRITEAOF·자동 재작성 시작 - rewriteAppendOnlyFileBackground
     * 완료 처리는 cron의 finishRewrite
     *
     * @return 실패 사유 - 시작했으면 null
     */
    public String rewriteInBackground(Keyspace keyspace, String trigger) {
        if (rewrite != null) {
            return "Background append only file rewriting already in progress";
        }
        try {
            Files.createDirectories(aofDir);
            selectedDb = -1;
            flush(true);
            openNewIncr();
        } catch (IOException e) {
            publishFailure("새 incr 파일을 열지 못함: " + e.getMessage());
            return "Can't open the append-only file: " + e.getMessage();
        }

        long start = System.nanoTime();
        // RDB preamble 이면 EOF·CRC64 는 쓰기 스레드에서 (자식 프로세스 몫)
        RdbWriter.Body rdb = config.rdbPreamble() ? rdbBody(keyspace) : null;
        byte[] commands = rdb == null ? AofRewriter.rewrite(keyspace) : null;
        int length = rdb != null ? rdb.finishedLength() : commands.length;
        long serializeNanos = System.nanoTime() - start;

        Path temp = config.dir().resolve("temp-rewriteaof-bg-" + ProcessHandle.current().pid() + ".aof");
        CompletableFuture<Void> written = CompletableFuture.runAsync(() -> {
            try {
                Files.write(temp, rdb != null ? rdb.finish() : commands);
                Fsync.file(temp);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }, rewriteThread);
        rewrite = new Rewrite(temp, written, start, currentSize);
        rewrites++;
        rewriteScheduled = false;
        if (events.enabled()) {
            events.publish(new Event.AofRewriteStarted(trigger, keyspace.size(), currentSize, serializeNanos,
                    length, channelName));
        }
        return null;
    }

    /** openNewIncrAofForAppend - AOF 꺼져 있으면 파일 없이 base만 */
    private void openNewIncr() throws IOException {
        if (!config.enabled()) {
            return;
        }
        // AOF_WAIT_REWRITE - manifest 밖 임시 incr 에 누적, 재작성 끝나면 새 incr 로 이름 변경
        AofManifest next = waitRewrite ? manifest : manifest.copy();
        String name = waitRewrite ? TEMP_INCR_NAME : next.newIncr();
        FileChannel opened = FileChannel.open(aofDir.resolve(name), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        if (!waitRewrite) {
            try {
                next.persist(aofDir);
            } catch (IOException e) {
                opened.close();
                throw e;
            }
        }
        // 옛 파일은 백그라운드에서 fsync 후 닫기 - aof_background_fsync_and_close
        FileChannel old = channel;
        String oldName = channelName;
        long oldSize = lastIncrSize;
        if (old != null) {
            fsyncThread.execute(() -> {
                long t = System.nanoTime();
                try {
                    old.force(false);
                    publishFsync(oldName, oldSize, System.nanoTime() - t);
                    old.close();
                } catch (IOException e) {
                    System.err.println("[glass-redis] 옛 AOF 파일 닫기 실패: " + e.getMessage());
                }
            });
            lastFsyncMillis = System.currentTimeMillis();
            lastFsyncSecond = lastFsyncMillis / 1000;
        }
        channel = opened;
        channelName = name;
        lastIncrSize = 0;
        lastIncrFsyncOffset = 0;
        syncedOffset.set(0);
        manifest = next;
    }

    /** backgroundRewriteDoneHandler - 임시 파일을 새 base로, 옛 base·incr는 history로 표시 후 삭제 */
    private void finishRewrite() {
        Rewrite done = rewrite;
        rewrite = null;
        if (done.written().isCompletedExceptionally()) {
            String reason = done.written().handle((ok, e) -> e == null ? "" : e.getCause() == null
                    ? e.getMessage() : e.getCause().getMessage()).join();
            publishFailure("임시 파일 쓰기 실패: " + reason);
            System.err.println("[glass-redis] AOF 재작성 실패: " + reason);
            consecutiveRewriteFailures++;
            retryIfWaiting();
            return;
        }

        AofManifest next = manifest.copy();
        String baseName = next.newBase(config.rdbPreamble());
        Path basePath = aofDir.resolve(baseName);
        String incrName = null;
        long baseBytes;
        try {
            Fsync.replace(done.temp(), basePath);
            baseBytes = Files.size(basePath);
            if (waitRewrite) {
                incrName = next.newIncr();
                Fsync.replace(aofDir.resolve(TEMP_INCR_NAME), aofDir.resolve(incrName));
            }
            next.markRewrittenIncrAsHistory(channel != null);
            next.persist(aofDir);
        } catch (IOException e) {
            publishFailure("새 base 로 교체 실패: " + e.getMessage());
            consecutiveRewriteFailures++;
            try {
                Files.deleteIfExists(basePath);
                if (incrName != null && Files.exists(aofDir.resolve(incrName))) {
                    // 임시 incr 로 되돌림 - 다음 재작성까지 계속 기록
                    Fsync.replace(aofDir.resolve(incrName), aofDir.resolve(TEMP_INCR_NAME));
                }
            } catch (IOException ignored) {
                // 다음 재작성이 덮어씀
            }
            retryIfWaiting();
            return;
        }
        if (incrName != null) {
            channelName = incrName;
        }
        manifest = next;
        consecutiveRewriteFailures = 0;
        if (config.enabled()) {
            currentSize = baseBytes + lastIncrSize;
            rewriteBaseSize = currentSize;
        }
        if (waitRewrite) {
            // AOF_WAIT_REWRITE → AOF_ON - 그사이 fsync 스레드가 갱신했을 수 있는 위치로
            waitRewrite = false;
            fsyncedReploff = fsyncedReploffPending.get();
        }

        // aofDelHistoryFiles
        List<String> removed = new ArrayList<>();
        for (AofManifest.FileInfo old : manifest.history()) {
            try {
                Files.deleteIfExists(aofDir.resolve(old.name()));
                removed.add(old.name());
            } catch (IOException e) {
                System.err.println("[glass-redis] 옛 AOF 파일 삭제 실패: " + old.name());
            }
        }
        manifest.clearHistory();
        try {
            manifest.persist(aofDir);
        } catch (IOException e) {
            System.err.println("[glass-redis] manifest 갱신 실패: " + e.getMessage());
        }
        if (events.enabled()) {
            events.publish(new Event.AofRewriteFinished(baseName, baseBytes, done.sizeBefore(), currentSize,
                    System.nanoTime() - done.startNanos(), List.copyOf(removed)));
        }
    }

    /** 동기화 뒤 첫 재작성 실패 - AOF_ON 으로 못 넘어가 다시 예약 (backgroundRewriteDoneHandler cleanup) */
    private void retryIfWaiting() {
        if (waitRewrite) {
            rewriteScheduled = true;
        }
    }

    private void publishFailure(String reason) {
        if (events.enabled()) {
            events.publish(new Event.AofRewriteFailed(reason));
        }
    }

    /** 대시보드용 - 실행 스레드에서 */
    public AofSnapshot snapshot() {
        List<AofSnapshot.FileView> files = new ArrayList<>();
        addFile(files, manifest.base());
        manifest.history().forEach(info -> addFile(files, info));
        manifest.incr().forEach(info -> addFile(files, info));
        long synced = channel == null ? 0 : syncedOffset.get();
        String status = !config.enabled() ? "OFF" : loadResult == null ? "NOT_EXIST" : loadResult.status().name();
        return new AofSnapshot(config.enabled(), config.fsync().configName(), files, currentSize, rewriteBaseSize,
                config.rewritePercentage(), config.rewriteMinSize(), rewrite != null,
                Math.max(0, lastIncrSize - synced),
                lastFsyncMillis == 0 ? -1 : System.currentTimeMillis() - lastFsyncMillis, status,
                loadResult == null ? 0 : loadResult.rdbKeys(), loadResult == null ? 0 : loadResult.commands(),
                loadResult == null ? 0 : loadResult.elapsedMillis(),
                loadResult == null ? null : loadResult.truncatedFile(), rewrites);
    }

    private void addFile(List<AofSnapshot.FileView> files, AofManifest.FileInfo info) {
        if (info == null) {
            return;
        }
        long bytes;
        if (info.name().equals(channelName)) {
            // 지금 쓰는 파일 - 버퍼 미포함, OS로 넘긴 만큼
            bytes = lastIncrSize;
        } else {
            try {
                bytes = Files.size(aofDir.resolve(info.name()));
            } catch (IOException e) {
                bytes = -1;
            }
        }
        files.add(new AofSnapshot.FileView(info.name(), info.type(), info.seq(), bytes));
    }

    /** 종료 - 남은 버퍼 write 후 fsync (prepareForShutdown) */
    @Override
    public void close() {
        flush(true);
        rewriteThread.shutdownNow();
        fsyncThread.shutdown();
        try {
            fsyncThread.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (channel != null) {
            try {
                channel.force(false);
                channel.close();
            } catch (IOException e) {
                System.err.println("[glass-redis] AOF 닫기 실패: " + e.getMessage());
            }
        }
        if (rewrite != null) {
            try {
                Files.deleteIfExists(rewrite.temp());
            } catch (IOException ignored) {
                // 다음 기동 때 덮어씀
            }
        }
    }
}
