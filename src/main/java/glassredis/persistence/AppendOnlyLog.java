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
     */
    public record Config(Path dir, boolean enabled, FsyncPolicy fsync, int rewritePercentage, long rewriteMinSize) {

        /** Redis 기본값 - appendonly no, everysec, 100%, 64MB */
        public static Config defaults(Path dir) {
            return new Config(dir, false, FsyncPolicy.EVERYSEC, 100, 64L * 1024 * 1024);
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
        manifest = AofManifest.load(aofDir);
        if (!config.enabled()) {
            return;
        }
        loadResult = new AofLoader(aofDir, registry, keyspace).load(manifest);
        if (loadResult.status() == AofLoader.Status.FAILED) {
            throw new IOException("AOF 복구 실패: " + loadResult.error());
        }
        currentSize = loadResult.bytes();
        rewriteBaseSize = loadResult.baseBytes();
        openOnStart(keyspace);
    }

    public AofLoader.Result loadResult() {
        return loadResult;
    }

    /** 데이터 없이 처음 켜지면 빈 base부터 - 이후 파일 구성이 항상 base + incr */
    private void openOnStart(Keyspace keyspace) throws IOException {
        Files.createDirectories(aofDir);
        if (manifest.isEmpty()) {
            String base = manifest.newBase();
            writeBase(aofDir.resolve(base), AofRewriter.rewrite(keyspace));
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
        if (!config.enabled()) {
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

    /** 명령 하나가 여러 전파를 낳으면 트랜잭션으로 - propagatePendingCommands */
    public void feedAll(List<List<byte[]>> commands) {
        if (!config.enabled() || commands.isEmpty()) {
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

    /**
     * beforeSleep - 버퍼를 OS로 write, 정책에 따라 fsync (flushAppendOnlyFile)
     * 쓰기 실패 시 버퍼 유지 후 다음 번에 재시도
     */
    public void flush() {
        if (channel == null) {
            return;
        }
        if (buffer.size() == 0) {
            // 쓸 것이 없어도 확정 안 된 바이트가 있으면 1초 주기 fsync
            if (config.fsync() == FsyncPolicy.EVERYSEC && lastIncrFsyncOffset != lastIncrSize
                    && System.currentTimeMillis() - lastFsyncMillis >= 1000) {
                scheduleFsync();
            }
            return;
        }
        byte[] bytes = buffer.toByteArray();
        try {
            ByteBuffer out = ByteBuffer.wrap(bytes);
            while (out.hasRemaining()) {
                channel.write(out);
            }
        } catch (IOException e) {
            System.err.println("[glass-redis] AOF 쓰기 실패, 다음에 다시 씁니다: " + e.getMessage());
            return;
        }
        buffer.reset();
        currentSize += bytes.length;
        lastIncrSize += bytes.length;
        if (events.enabled()) {
            events.publish(Event.aofWritten(bufferedCommands, bytes.length, channelName, lastIncrSize, currentSize));
        }
        bufferedCommands.clear();

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
                lastFsyncMillis = System.currentTimeMillis();
                publishFsync(channelName, lastIncrSize, System.nanoTime() - start);
            }
            case EVERYSEC -> {
                if (System.currentTimeMillis() - lastFsyncMillis >= 1000) {
                    scheduleFsync();
                }
            }
            case NO -> {
                // OS가 알아서 디스크로
            }
        }
    }

    /** aof_background_fsync - 진행 중이면 이번 회차 건너뜀 */
    private void scheduleFsync() {
        lastFsyncMillis = System.currentTimeMillis();
        if (!fsyncInProgress.compareAndSet(false, true)) {
            return;
        }
        FileChannel target = channel;
        String name = channelName;
        long offset = lastIncrSize;
        lastIncrFsyncOffset = offset;
        fsyncThread.execute(() -> {
            long start = System.nanoTime();
            try {
                target.force(false);
                syncedOffset.accumulateAndGet(offset, Math::max);
                publishFsync(name, offset, System.nanoTime() - start);
            } catch (IOException e) {
                System.err.println("[glass-redis] AOF fsync 실패: " + e.getMessage());
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
        if (config.enabled() && rewrite == null && config.rewritePercentage() > 0
                && currentSize > config.rewriteMinSize()) {
            long base = rewriteBaseSize > 0 ? rewriteBaseSize : 1;
            long growth = currentSize * 100 / base - 100;
            if (growth >= config.rewritePercentage()) {
                rewriteInBackground(keyspace, "자동 (" + growth + "% 증가)");
            }
        }
    }

    public boolean rewriting() {
        return rewrite != null;
    }

    /** BGSAVE 중 BGREWRITEAOF - 끝나면 시작 (aof_rewrite_scheduled) */
    public void scheduleRewrite() {
        rewriteScheduled = true;
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
            flush();
            openNewIncr();
        } catch (IOException e) {
            publishFailure("새 incr 파일을 열지 못함: " + e.getMessage());
            return "Can't open the append-only file: " + e.getMessage();
        }

        long start = System.nanoTime();
        byte[] content = AofRewriter.rewrite(keyspace);
        long serializeNanos = System.nanoTime() - start;

        Path temp = config.dir().resolve("temp-rewriteaof-bg-" + ProcessHandle.current().pid() + ".aof");
        CompletableFuture<Void> written = CompletableFuture.runAsync(() -> {
            try {
                Files.write(temp, content);
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
                    content.length, channelName));
        }
        return null;
    }

    /** openNewIncrAofForAppend - AOF 꺼져 있으면 파일 없이 base만 */
    private void openNewIncr() throws IOException {
        if (!config.enabled()) {
            return;
        }
        AofManifest next = manifest.copy();
        String name = next.newIncr();
        FileChannel opened = FileChannel.open(aofDir.resolve(name), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            next.persist(aofDir);
        } catch (IOException e) {
            opened.close();
            throw e;
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
            return;
        }

        AofManifest next = manifest.copy();
        String baseName = next.newBase();
        Path basePath = aofDir.resolve(baseName);
        long baseBytes;
        try {
            Fsync.replace(done.temp(), basePath);
            baseBytes = Files.size(basePath);
            next.markRewrittenIncrAsHistory(channel != null);
            next.persist(aofDir);
        } catch (IOException e) {
            publishFailure("새 base 로 교체 실패: " + e.getMessage());
            try {
                Files.deleteIfExists(basePath);
            } catch (IOException ignored) {
                // 다음 재작성이 덮어씀
            }
            return;
        }
        manifest = next;
        if (config.enabled()) {
            currentSize = baseBytes + lastIncrSize;
            rewriteBaseSize = currentSize;
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
                loadResult == null ? 0 : loadResult.commands(), loadResult == null ? 0 : loadResult.elapsedMillis(),
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
        flush();
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
