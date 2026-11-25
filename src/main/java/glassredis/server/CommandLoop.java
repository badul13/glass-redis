package glassredis.server;

import glassredis.command.Client;
import glassredis.command.Command;
import glassredis.command.CommandRegistry;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.command.Propagation;
import glassredis.command.impl.PingCommand;
import glassredis.observe.AofSnapshot;
import glassredis.observe.RdbSnapshot;
import glassredis.observe.ReplicationSnapshot;
import glassredis.observe.Event;
import glassredis.observe.EventBus;
import glassredis.persistence.AppendOnlyLog;
import glassredis.persistence.Fsync;
import glassredis.persistence.RdbLoader;
import glassredis.persistence.Snapshot;
import glassredis.replication.Replicator;
import glassredis.resp.RespValue;
import glassredis.store.ActiveExpireCycle;
import glassredis.store.Keyspace;
import glassredis.store.encoding.Dict;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * 모든 명령을 실행 스레드 하나에서 순차 실행
 * 키스페이스는 이 스레드 전용 - 락 없음
 * 커넥션 스레드는 큐 투입 후 응답 대기, 소켓 입출력도 커넥션 스레드 담당
 *
 * <pre>
 *   커넥션 스레드들 ─┐
 *   주기 타이머  ────┴─▶ 큐 ─▶ 실행 스레드 ─▶ 큐가 비면 beforeSleep: AOF write 후 응답 전달
 * </pre>
 *
 * <p>실행 스레드는 플랫폼 스레드 - 계산만 하므로 가상 스레드 불필요
 */
final class CommandLoop implements AutoCloseable {

    /** server.hz - CONFIG_DEFAULT_HZ, 주기 작업 박자·SLOW 만료 시간 한도 */
    static final int HZ = 10;

    /** 무제한 큐 - 커넥션당 명령 묶음 1개 + 주기 작업 1개라 최대 커넥션 수 + 1 */
    private final BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();

    private final Thread thread = Thread.ofPlatform().name("glass-redis-executor").unstarted(this::runLoop);

    /** 박자 전용 - 키스페이스 직접 접근 금지, 작업은 큐 투입만 */
    private final ScheduledExecutorService cronTimer = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("glass-redis-cron-timer").daemon(true).factory());

    /** 실행 스레드 지연 시 주기 작업 중복 적재 방지 */
    private final AtomicBoolean cronQueued = new AtomicBoolean();

    /** 내부 키스페이스는 실행 스레드 전용 */
    private final Context context;

    private final ActiveExpireCycle activeExpire;

    private final EventBus events;

    private final AppendOnlyLog aof;

    /** AOF 재작성·BGSAVE 조율 */
    private final Persistence persistence;

    /** 마스터 쪽 복제 */
    private final Replicator master;

    /** server.cronloops - 1초 주기 작업 판단 */
    private long cronLoops;

    /** 레플리카가 받은 RDB 를 둘 곳 - null 이면 받은 임시 파일에서 바로 */
    private final Snapshot snapshot;

    /** 마스터 스트림의 명령 이름 찾기 */
    private CommandRegistry registry = CommandRegistry.withBuiltins();

    /** REPLCONF listening-port 로 알릴 포트 */
    private volatile int port;

    /** 실행 단위 안의 전파 - null 이면 명령마다 바로 (also_propagate 를 단위 끝에 한꺼번에) */
    private List<List<byte[]>> unit;

    private static final RespValue READONLY =
            new RespValue.Err("READONLY You can't write against a read only replica.");

    /** 실행했지만 아직 안 보낸 응답 - AOF write 뒤 전달 (beforeSleep의 handleClientsWithPendingWrites) */
    private final List<Runnable> pendingReplies = new ArrayList<>();

    /** 실행 스레드 사망 시 호출 - 서버 종료로 연결 */
    private final Runnable onFatalError;

    /** 실행 스레드 사망 - 상태 신뢰 불가라 종료 시 저장 생략 */
    private volatile boolean fatal;

    private volatile boolean running;
    /** repl_ignore_disk_write_error */
    private final boolean ignoreDiskWriteErrors;
    /** "디스크 오류 중 마스터 명령 실행" 경고 시각 (밀리초) - 10초에 한 번 */
    private long lastDiskErrorLogMillis;

    private static final RespValue LOADING = new RespValue.Err("LOADING Redis is loading the dataset in memory");

    /** server.loading - 기동 복구 또는 레플리카 동기화 RDB 로드 중 */
    private volatile boolean loading;
    /** loading 전환과 로딩 중 명령 넣기·옮기기를 묶는 잠금 */
    private final Object loadingLock = new Object();
    /** 로딩 중 통과한 명령 - 로더의 진행 콜백이 로딩 스레드에서 실행 */
    private final List<Runnable> loadingTasks = new ArrayList<>();
    /** beginLoading 때 찍은 거부 상태 - 접속 스레드가 읽음 */
    private volatile RespValue loadingMisconf;
    private volatile boolean loadingReadOnly;

    /** 쓰기 명령 보류 표시 - 응답 아님, runBatch 가 나머지를 pausedBatches 로 */
    private static final RespValue POSTPONED = new RespValue.Err("postponed");

    /** shutdown_asap - 신호 받음, cron 이 종료 시도 (신호 처리기에선 플래그만) */
    private volatile boolean shutdownAsap;
    /** shutdown_mstime - 0 이 아니면 레플리카 기다리는 중 (isShutdownInitiated) */
    private long shutdownDeadlineMillis;
    /** shutdown-timeout (초) - 0 이면 레플리카 안 기다림 */
    private volatile long shutdownTimeoutSeconds = 10;
    /** PAUSE_DURING_SHUTDOWN - 쓰기 명령·만료 멈춤 */
    private boolean writesPaused;
    /** 멈춘 동안 보류한 묶음 나머지 - 종료 취소 시 다시 실행 */
    private final List<Runnable> pausedBatches = new ArrayList<>();
    /** finishShutdown 성공 - 저장까지 끝, 서버 정리와 프로세스 종료는 호출한 쪽 */
    private volatile Runnable onShutdownFinished = () -> {
    };

    /** stop() 한 번만 */
    private final AtomicBoolean stopping = new AtomicBoolean();
    /** stop() 마무리(저장 포함) 끝 */
    private final CountDownLatch stopped = new CountDownLatch(1);

    CommandLoop(Keyspace keyspace, EventBus events, Runnable onFatalError) {
        this(keyspace, events, new AppendOnlyLog(AppendOnlyLog.Config.defaults(Path.of(".")), events), null,
                onFatalError);
    }

    /**
     * @param keyspace start() 이후 실행 스레드 전용
     * @param aof      복구까지 마친 상태 - 이후 실행 스레드 전용
     * @param snapshot null이면 SAVE·BGSAVE 실패
     */
    CommandLoop(Keyspace keyspace, EventBus events, AppendOnlyLog aof, Snapshot snapshot, Runnable onFatalError) {
        this(keyspace, events, aof, snapshot, Replicator.Config.defaults(), onFatalError);
    }

    /** @param replication 복제 설정 */
    CommandLoop(Keyspace keyspace, EventBus events, AppendOnlyLog aof, Snapshot snapshot,
                Replicator.Config replication, Runnable onFatalError) {
        this.aof = aof;
        this.persistence = new Persistence(keyspace, aof, snapshot);
        this.master = new Replicator(replication, keyspace, persistence, queue::add,
                () -> System.currentTimeMillis() / 1000);
        persistence.replication(master);
        if (snapshot != null) {
            // 모든 RDB 저장에 복제 위치 (rdbPopulateSaveInfo)
            snapshot.replInfo(master::saveInfo);
        }
        this.context = new Context(keyspace, new Propagation(), persistence, master);
        this.snapshot = snapshot;
        master.host(new Host());
        aof.replOffset(master::masterReplOffset);
        this.events = events;
        // 만료 삭제는 DEL로 전파 - 명령 중이면 그 명령과 묶고, 주기적 만료면 단독
        keyspace.setExpiredListener(key -> {
            List<byte[]> del = List.of("DEL".getBytes(StandardCharsets.US_ASCII), key.bytes());
            if (context.propagation().active()) {
                context.propagation().also(del);
            } else {
                propagate(List.of(del));
            }
        });
        this.activeExpire = new ActiveExpireCycle(keyspace, events, HZ);
        // lookupKey 의 lru 갱신 생략 판단 (hasActiveChildProcess), 쓰기 조회의 만료 키 강제 삭제 판단 (repl_slave_ro)
        keyspace.setChildActiveCheck(persistence::childActive);
        keyspace.setReplicaReadOnly(replication.replicaReadOnly());
        this.ignoreDiskWriteErrors = replication.ignoreDiskWriteErrors();
        this.onFatalError = onFatalError;
    }

    void registry(CommandRegistry registry) {
        this.registry = registry;
    }

    void port(int port) {
        this.port = port;
    }

    /** 복제 상태 - 기동 시 replicaof·복구 정보 반영용, start() 전에만 */
    Replicator replicator() {
        return master;
    }

    /** 레플리카 연결에서 온 빈 줄 - RDB 로드 중인 레플리카가 살아 있다는 신호 */
    void replicaNewline(Client client) {
        queue.add(() -> master.replicaNewline(client));
    }

    /** 레플리카일 때 실행 스레드가 해 주는 일 */
    private final class Host implements Replicator.ReplicaHost {

        @Override
        public RespValue executeFromMaster(Client client, List<byte[]> argv) {
            String name = new String(argv.get(0), StandardCharsets.US_ASCII);
            Command command = registry.find(name);
            if (command == null) {
                return Errors.unknownCommand(argv.get(0), argv.subList(1, argv.size()));
            }
            return execute(command, argv, client);
        }

        @Override
        public void beginUnit() {
            unit = new ArrayList<>();
            // 실행 단위 시작 - EXEC 안의 명령들은 같은 시각 (enterExecutionUnit 중첩)
            context.keyspace().updateCommandTime();
        }

        @Override
        public void endUnit() {
            List<List<byte[]>> collected = unit;
            unit = null;
            propagate(collected);
        }

        @Override
        public String loadFromMaster(Path received) {
            Keyspace keyspace = context.keyspace();
            // stopAppendOnly - 비우는 동안의 DEL 등은 기록 안 함, 끝나면 재작성으로 새로
            aof.suspend();
            log("MASTER <-> REPLICA 동기화: 이전 데이터를 비웁니다");
            keyspace.clear();
            Path target = snapshot != null ? snapshot.file() : received;
            try {
                if (!target.equals(received)) {
                    Files.move(received, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                return "받은 RDB 를 " + target + " 로 옮기지 못함: " + e.getMessage();
            }
            try {
                // readSyncBulkPayload - 이름 바꾼 뒤 폴더 fsync, 실패면 동기화 취소
                Fsync.directoryOf(target);
            } catch (IOException e) {
                return "RDB 폴더를 fsync 하지 못함: " + e.getMessage();
            }
            log("MASTER <-> REPLICA 동기화: 메모리로 로드합니다");
            // rdbLoad 의 startLoading - 로드 동안 다른 클라이언트는 -LOADING (접속 스레드가 판정)
            RdbLoader.Result loaded;
            beginLoading(master.deniesWrite(new Client(-1, null, 0)));
            try {
                RdbLoader.Options options = RdbLoader.Options.REPLICA.withProgress(CommandLoop.this::processEventsWhileLoading);
                loaded = snapshot != null
                        ? snapshot.load(keyspace, options)
                        : RdbLoader.load(target, keyspace, options);
            } finally {
                endLoading();
            }
            if (snapshot == null) {
                try {
                    Files.deleteIfExists(target);
                } catch (IOException ignored) {
                    // 임시 파일 - 다음 동기화에 영향 없음
                }
            }
            if (loaded.status() != RdbLoader.Status.OK) {
                keyspace.clear();
                return loaded.error();
            }
            log("RDB 로드 완료, 키 %d개 (redis-ver %s)", loaded.keys(), loaded.createdBy());
            return null;
        }

        @Override
        public void afterSync() {
            if (aof.suspended()) {
                String failure = aof.resumeAfterSync(context.keyspace(), persistence.childActive());
                if (failure != null) {
                    log("동기화 뒤 AOF 다시 시작 실패: %s", failure);
                }
            }
        }

        @Override
        public long fsyncedReploff() {
            return aof.fsyncedReploff();
        }

        @Override
        public int listeningPort() {
            return port;
        }
    }

    void start() {
        running = true;
        thread.start();
        long periodMillis = 1000 / HZ;
        cronTimer.scheduleAtFixedRate(this::requestCron, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * @param argv         이름 포함 전체 인자 - AOF에는 받은 그대로 기록
     * @param connectionId 이벤트 표시용 - 실행과 무관
     */
    CompletableFuture<RespValue> submit(Command command, List<byte[]> argv, long connectionId) {
        return submitAll(List.of(new Request(command, argv)), new Client(connectionId, null, 0))
                .thenApply(replies -> replies.get(0));
    }

    /**
     * 한 커넥션이 한 번에 받은 명령들 - 큐 작업 1개로 연달아 실행 (processInputBuffer)
     * 사이에 다른 커넥션 명령·주기 작업 안 끼어듦, 응답은 beforeSleep 에서 한꺼번에 전달
     *
     * @param requests 받은 순서, 비어 있으면 안 됨
     * @param client   보낸 커넥션 - 복제 명령이 상태를 바꿈
     * @return 요청과 같은 순서의 응답 - 레플리카가 된 연결이면 전부 NO_REPLY
     */
    CompletableFuture<List<RespValue>> submitAll(List<Request> requests, Client client) {
        return submitBatch(requests, client).thenCompose(CommandLoop::collect);
    }

    /**
     * 묶음 응답 - 쓰기 보류로 멈추면 그때까지의 응답 + 나머지 (rest), 끝까지 실행했으면 rest 는 null
     * 보류 지점 앞 명령의 응답은 먼저 보냄 - Redis 도 보류된 클라이언트의 앞 응답은 beforeSleep 에서 씀
     */
    record Replies(List<RespValue> values, CompletableFuture<Replies> rest) {
    }

    /** 한 커넥션이 한 번에 받은 명령들 - 응답은 부분씩 (쓰기 보류 시) */
    CompletableFuture<Replies> submitBatch(List<Request> requests, Client client) {
        CompletableFuture<Replies> replies = new CompletableFuture<>();
        queue.add(() -> {
            // 묶음 시작 시 보낼 응답 없음 - 앞 묶음은 이미 flush 됨
            client.pendingReplies(false);
            runBatch(requests, 0, client, replies);
        });
        return replies;
    }

    private static CompletableFuture<List<RespValue>> collect(Replies part) {
        if (part.rest() == null) {
            return CompletableFuture.completedFuture(part.values());
        }
        return part.rest().thenCompose(CommandLoop::collect).thenApply(tail -> {
            List<RespValue> all = new ArrayList<>(part.values());
            all.addAll(tail);
            return all;
        });
    }

    /**
     * 묶음 실행 - from 번째부터
     * 종료 대기로 쓰기가 멈춘 동안 쓰기 명령을 만나면 거기서 멈춤 - 앞 응답은 지금, 나머지는 재개 때 (blockPostponeClient)
     */
    private void runBatch(List<Request> requests, int from, Client client, CompletableFuture<Replies> out) {
        List<RespValue> results = new ArrayList<>(requests.size() - from);
        for (int i = from; i < requests.size(); i++) {
            Request request = requests.get(i);
            RespValue reply = request.command() == null
                    ? Errors.unknownCommand(request.argv().get(0),
                            request.argv().subList(1, request.argv().size()))
                    : execute(request.command(), request.argv(), client);
            if (reply == POSTPONED) {
                int resumeAt = i;
                CompletableFuture<Replies> rest = new CompletableFuture<>();
                pausedBatches.add(() -> runBatch(requests, resumeAt, client, rest));
                pendingReplies.add(() -> out.complete(new Replies(results, rest)));
                return;
            }
            if (!(reply instanceof RespValue.NoReply)) {
                if (client.isReplica()) {
                    // 복제 링크는 응답용이 아님 - 응답이 생기면 끊음
                    master.replicaGeneratedReply(client, new String(request.argv().get(0),
                            StandardCharsets.ISO_8859_1));
                    reply = RespValue.NO_REPLY;
                } else {
                    client.pendingReplies(true);
                }
            }
            results.add(reply);
        }
        pendingReplies.add(() -> out.complete(new Replies(results, null)));
    }

    /** 커넥션이 끝남 - 레플리카였으면 정리 */
    void clientClosed(Client client) {
        queue.add(() -> master.clientClosed(client));
    }

    /**
     * 실행할 명령 하나
     *
     * @param command 없는 명령이면 null - unknown command 에러 응답
     * @param argv    이름 포함 전체 인자 - AOF에는 받은 그대로 기록
     */
    record Request(Command command, List<byte[]> argv) {
    }

    /**
     * 대시보드용 키스페이스 읽기 - 실행 스레드에서 수행
     * 명령과 같은 큐 - 중간 상태 노출 없음
     *
     * @param reader 읽기 전용
     */
    <T> CompletableFuture<T> inspect(Function<Keyspace, T> reader) {
        CompletableFuture<T> result = new CompletableFuture<>();
        queue.add(() -> result.complete(reader.apply(context.keyspace())));
        return result;
    }

    /** 대시보드용 AOF 상태 - 실행 스레드에서 */
    CompletableFuture<AofSnapshot> aofSnapshot() {
        CompletableFuture<AofSnapshot> result = new CompletableFuture<>();
        queue.add(() -> result.complete(aof.snapshot()));
        return result;
    }

    /** 대시보드용 RDB 상태 - 실행 스레드에서, RDB 없으면 null */
    CompletableFuture<RdbSnapshot> rdbSnapshot() {
        CompletableFuture<RdbSnapshot> result = new CompletableFuture<>();
        queue.add(() -> result.complete(persistence.rdbSnapshot()));
        return result;
    }

    /** 대시보드용 복제 상태 - 실행 스레드에서 */
    CompletableFuture<ReplicationSnapshot> replicationSnapshot() {
        CompletableFuture<ReplicationSnapshot> result = new CompletableFuture<>();
        queue.add(() -> result.complete(master.view()));
        return result;
    }

    /**
     * 저장 없이 멈춤 - 비정상 종료와 같음
     * 큐에 남은 future는 미완료로 방치
     * 대기 중 커넥션 스레드는 RedisServer.close()의 인터럽트로 해제
     * 실행 스레드가 멈춘 뒤 AOF 마무리 - 남은 버퍼 write와 fsync
     */
    @Override
    public void close() {
        stop(false);
    }

    /** 정상 종료 - close() 에 더해 save 규칙 있으면 마지막 RDB (prepareForShutdown) */
    void shutdown() {
        stop(true);
    }

    /**
     * 한 번만 - 치명 오류(실행 스레드)와 종료 훅(다른 스레드)이 겹쳐도 저장은 한 번
     * 실행 스레드 종료를 끝까지 기다린 뒤 저장 - 시한 대기 후 진행 시 명령 실행과 직렬화가 동시에 돎
     * 실행 스레드 자신이 호출하면 인터럽트 안 함 - 인터럽트 상태의 FileChannel 쓰기는 ClosedByInterruptException
     */
    private void stop(boolean save) {
        if (!stopping.compareAndSet(false, true)) {
            // 이미 종료 중 - 다른 스레드면 마무리까지 대기 (훅이 먼저 끝나 저장 도중 JVM 종료 방지)
            if (Thread.currentThread() != thread) {
                awaitStopped();
            }
            return;
        }
        boolean interrupted = false;
        try {
            running = false;
            cronTimer.shutdownNow();
            if (thread != Thread.currentThread()) {
                thread.interrupt();
                interrupted = joinLoopThread();
            }
            master.close();
            if (save && !fatal) {
                persistence.shutdown();
            } else {
                persistence.close();
            }
        } finally {
            stopped.countDown();
            // 저장 끝난 뒤 복원 - 저장 중 인터럽트 상태면 파일 쓰기 실패
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** @return 대기 중 인터럽트 받았는지 - 받아도 끝까지 대기 */
    private boolean joinLoopThread() {
        boolean interrupted = false;
        while (thread.isAlive()) {
            try {
                thread.join(2000);
                if (thread.isAlive()) {
                    System.err.println("[glass-redis] 실행 중인 작업 끝나기 대기 - 저장과 동시 실행 방지");
                }
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        return interrupted;
    }

    private void awaitStopped() {
        boolean interrupted = false;
        while (true) {
            try {
                stopped.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** 타이머 스레드에서 호출 */
    private void requestCron() {
        if (cronQueued.compareAndSet(false, true)) {
            queue.add(() -> {
                cronQueued.set(false);
                serverCron();
            });
        }
    }

    /**
     * SLOW 만료 + 해시 테이블 축소 + 1ms 리해시 + 저장 - serverCron, databasesCron
     * 리해시도 여기서 진행 - 명령이 뜸할 때 지연 방지
     */
    private void serverCron() {
        Keyspace keyspace = context.keyspace();
        // server.lruclock = getLRUClock() - databasesCron 보다 먼저
        keyspace.updateLruClock();
        // activeExpireCycle - 만료가 멈춘 동안(종료 대기) 쉼
        if (!master.isReplica() && !keyspace.expirePaused()) {
            activeExpire.run(ActiveExpireCycle.Kind.SLOW);
        }
        // databasesCron - 자식 작업 중엔 축소·rehash 생략 (copy-on-write 회피)
        if (!persistence.childActive()) {
            keyspace.tryResizeHashTables();
            keyspace.incrementallyRehash();
        }
        // serverCron 의 종료 처리 - 신호 처리기는 플래그만, 실제 종료는 여기서
        if (shutdownAsap && shutdownDeadlineMillis == 0) {
            if (prepareForShutdown()) {
                onShutdownFinished.run();
                return;
            }
        } else if (shutdownDeadlineMillis != 0) {
            if (System.currentTimeMillis() >= shutdownDeadlineMillis || master.readyToShutdown()) {
                if (finishShutdown()) {
                    onShutdownFinished.run();
                    return;
                }
            }
        }
        persistence.cron();
        // fsync 가 늦어 미룬 AOF 쓰기 재시도
        aof.cronFlush();
        // run_with_period(1000) - 첫 호출부터 HZ 번마다
        if (cronLoops % HZ == 0) {
            master.cron();
        }
        persistence.scheduledBgsave();
        cronLoops++;
    }

    /**
     * 큐가 비어 쉬기 직전 - Redis beforeSleep 순서
     * FAST 만료(DEL 전파 생길 수 있음) → AOF write → 레플리카에 스트림 → 응답 전달
     */
    private void beforeSleep() {
        // 레플리카는 스스로 만료시키지 않음 - 마스터의 DEL 을 기다림
        if (!master.isReplica() && !context.keyspace().expirePaused()) {
            activeExpire.run(ActiveExpireCycle.Kind.FAST);
        }
        aof.flush();
        aof.publishFsyncedReploff();
        master.beforeSleep();
        if (!pendingReplies.isEmpty()) {
            pendingReplies.forEach(Runnable::run);
            pendingReplies.clear();
        }
    }

    private void runLoop() {
        updateDictResizePolicy();
        while (running) {
            try {
                // aeMain 한 바퀴 - beforeSleep → 대기 → afterSleep → 그때 준비된 이벤트 전부 처리
                // 큐가 빌 때만 beforeSleep 하면 쉬지 않는 부하에서 AOF write·레플리카 전송·응답이 무한히 밀림
                beforeSleep();
                Runnable task = queue.take();
                // afterSleep - 명령 밖에서 쓸 시각 스냅샷 갱신
                context.keyspace().updateCommandTime();
                // 이번 바퀴에 처리할 몫 - 처리 중 새로 들어온 것은 다음 바퀴 (epoll 이 돌려준 이벤트만 처리)
                int fired = queue.size();
                runTask(task);
                for (int i = 0; i < fired && running; i++) {
                    Runnable next = queue.poll();
                    if (next == null) {
                        break;
                    }
                    runTask(next);
                }
            } catch (InterruptedException e) {
                return; // close()
            } catch (Throwable fatal) {
                // Error 또는 내부 작업 예외 - 상태 신뢰 불가
                // 이 스레드만 죽으면 모든 클라이언트 무한 대기 - 서버 전체 종료
                panic(fatal);
                return;
            }
        }
    }

    /** server.loading - 기동 복구 또는 레플리카 동기화 RDB 로드 중 */
    boolean loading() {
        return loading;
    }

    /**
     * startLoading - 로딩하는 스레드에서 (기동 때는 실행 스레드 시작 전 main, 동기화 때는 실행 스레드)
     * 거부 판정에 쓸 상태를 지금 찍어 둠 - 접속 스레드는 이 값만 읽음 (로드 동안 안 바뀜)
     */
    void beginLoading(boolean readOnlyReplica) {
        loadingMisconf = persistence.denyWrites();
        loadingReadOnly = readOnlyReplica;
        synchronized (loadingLock) {
            loading = true;
        }
    }

    /**
     * processEventsWhileBlocked - 로더가 2MB·1024 명령마다 로딩 스레드에서 부름
     * 그사이 들어온 loading 플래그 명령(ECHO·ROLE…)을 실행하고 응답까지 전달
     */
    void processEventsWhileLoading() {
        List<Runnable> tasks;
        synchronized (loadingLock) {
            tasks = List.copyOf(loadingTasks);
            loadingTasks.clear();
        }
        if (tasks.isEmpty()) {
            return;
        }
        tasks.forEach(Runnable::run);
        pendingReplies.forEach(Runnable::run);
        pendingReplies.clear();
    }

    /** stopLoading - 남은 것은 실행 큐로 (로딩 끝난 뒤 첫 작업들) */
    void endLoading() {
        processEventsWhileLoading();
        synchronized (loadingLock) {
            loading = false;
            queue.addAll(loadingTasks);
            loadingTasks.clear();
        }
    }

    /**
     * 로딩 중 processCommand - 접속 스레드에서
     * 순서: 인자 수 → MISCONF → READONLY → LOADING, loading 플래그 명령은 통과 (null)
     */
    RespValue rejectWhileLoading(Command command, List<byte[]> argv, Client client) {
        RespValue reply = Command.checkArity(command, argv.size());
        if (reply != null) {
            return reply;
        }
        if ((command.writes() || command instanceof PingCommand) && loadingMisconf != null) {
            return loadingMisconf;
        }
        if (command.writes() && loadingReadOnly) {
            return READONLY;
        }
        return command.allowedWhileLoading() ? null : LOADING;
    }

    /**
     * 로딩 중 통과한 명령 하나 - 로더의 다음 진행 콜백에서 실행 (로딩이 이미 끝났으면 실행 큐로)
     * 확인과 넣기가 endLoading 의 옮기기와 같은 잠금 - 끝나는 순간에 들어와도 빠지지 않음
     */
    CompletableFuture<List<RespValue>> submitWhileLoading(Request request, Client client) {
        CompletableFuture<Replies> out = new CompletableFuture<>();
        Runnable task = () -> {
            client.pendingReplies(false);
            runBatch(List.of(request), 0, client, out);
        };
        synchronized (loadingLock) {
            if (loading) {
                loadingTasks.add(task);
            } else {
                queue.add(task);
            }
        }
        return out.thenCompose(CommandLoop::collect);
    }

    /**
     * sigShutdownHandler - 신호 스레드에서 호출, 플래그만
     * 종료 진행 중 SIGINT 를 또 받으면 저장·레플리카 대기 없이 즉시 종료 (exit 1)
     *
     * @return 즉시 종료해야 하면 true - 호출한 쪽이 halt
     */
    boolean requestShutdown(boolean sigint) {
        if (shutdownAsap && sigint) {
            System.err.println("[glass-redis] You insist... exiting now.");
            persistence.removeTempFiles();
            return true;
        }
        System.err.println(sigint ? "[glass-redis] Received SIGINT scheduling shutdown..."
                : "[glass-redis] Received SIGTERM scheduling shutdown...");
        shutdownAsap = true;
        return false;
    }

    void shutdownTimeout(long seconds) {
        shutdownTimeoutSeconds = seconds;
    }

    /** finishShutdown 성공 뒤 실행 스레드에서 호출 - 서버 정리·프로세스 종료 */
    void onShutdownFinished(Runnable action) {
        onShutdownFinished = action;
    }

    /**
     * prepareForShutdown - 뒤처진 레플리카가 있으면 GETACK 보내고 쓰기·만료 멈춘 채 shutdown-timeout 까지 대기
     *
     * @return 종료 준비 끝(저장까지) - 대기 시작이거나 실패면 false
     */
    private boolean prepareForShutdown() {
        log("User requested shutdown...");
        if (shutdownTimeoutSeconds != 0 && !master.readyToShutdown()) {
            shutdownDeadlineMillis = System.currentTimeMillis() + shutdownTimeoutSeconds * 1000;
            master.sendGetack();
            pauseForShutdown(true);
            log("Waiting for replicas before shutting down.");
            return false;
        }
        return finishShutdown();
    }

    /**
     * finishShutdown - 레플리카 상태 기록, 자식 작업 정리, AOF fsync, 마지막 RDB
     * 실패하면 종료 취소 후 계속 운영 (cancelShutdown)
     */
    private boolean finishShutdown() {
        master.logReplicasAtShutdown();
        String failure = persistence.finishShutdown();
        if (failure != null) {
            log("Errors trying to shut down the server. Check the logs for more information.");
            cancelShutdown();
            return false;
        }
        log("Redis is now ready to exit, bye bye...");
        return true;
    }

    /** cancelShutdown - 대기 해제, 보류한 쓰기 재개 */
    private void cancelShutdown() {
        shutdownAsap = false;
        shutdownDeadlineMillis = 0;
        pauseForShutdown(false);
    }

    /** pauseActions(PAUSE_DURING_SHUTDOWN, PAUSE_ACTIONS_CLIENT_WRITE_SET) - 쓰기 명령·만료 */
    private void pauseForShutdown(boolean pause) {
        writesPaused = pause;
        context.keyspace().setExpirePaused(pause);
        if (!pause && !pausedBatches.isEmpty()) {
            List<Runnable> resume = List.copyOf(pausedBatches);
            pausedBatches.clear();
            resume.forEach(queue::add);
        }
    }

    private void runTask(Runnable task) {
        task.run();
        // 작업 안에서 자식 작업이 시작·종료됐을 수 있음
        updateDictResizePolicy();
    }

    /** serverPanic - 저장 없이 서버 종료, 실행 스레드에서 호출 */
    private void panic(Throwable cause) {
        System.err.println("[glass-redis] 실행 스레드에서 치명적 오류가 발생해 서버를 종료합니다");
        cause.printStackTrace();
        this.fatal = true;
        onFatalError.run();
    }

    /**
     * propagatePendingCommands - 둘 이상이면 MULTI/EXEC 로 감쌈
     * AOF 도 복제도 꺼져 있으면 아무것도 안 함 (shouldPropagate)
     */
    private void propagate(List<List<byte[]>> commands) {
        if (commands.isEmpty() || (!aof.enabled() && !master.propagating())) {
            return;
        }
        Propagation.emit(commands, this::propagateNow);
    }

    /** propagateNow - AOF 버퍼와 복제 스트림에 같이 */
    private void propagateNow(int dbid, List<byte[]> argv) {
        aof.feed(dbid, argv);
        master.feed(dbid, argv);
    }

    /** call() - 실행 후 dirty면 AOF·레플리카로 전파 */
    private RespValue execute(Command command, List<byte[]> argv, Client client) {
        long connectionId = client.id();
        context.client(client);
        // 관측 꺼짐 시 nanoTime() 비용도 생략
        long startNanos = events.enabled() ? System.nanoTime() : 0;
        List<byte[]> args = argv.subList(1, argv.size());

        // processCommand 순서 - 인자 수 검사 → 저장 실패 중이면 쓰기 명령·PING 거부
        // 마스터가 보낸 건 거부 대신 (mustObeyClient) - 디스크 오류 중 쓰기 명령이면 종료 또는 경고
        RespValue reply = Command.checkArity(command, argv.size());
        if (reply == null && client.isMaster() && (command.writes() || command instanceof PingCommand)
                && persistence.denyWrites() != null) {
            if (!ignoreDiskWriteErrors && !(command instanceof PingCommand)) {
                // serverPanic - 여기서 바로 종료, 던지기만 하면 MasterLink 가 링크 끊김으로 바꿔 재접속
                IllegalStateException cause = new IllegalStateException("Replica was unable to write command to disk.");
                panic(cause);
                throw cause;
            }
            long nowMillis = System.currentTimeMillis();
            if (nowMillis > lastDiskErrorLogMillis + 10_000) {
                lastDiskErrorLogMillis = nowMillis;
                log("디스크에 쓸 수 없는 상태에서 마스터 명령을 실행합니다");
            }
        }
        if (reply == null && !client.isMaster()) {
            reply = command.writes() || command instanceof PingCommand ? persistence.denyWrites() : null;
            if (reply == null && command.writes() && master.deniesWrite(client)) {
                reply = READONLY;
            }
            // processCommand 끝 - 종료 대기로 쓰기가 멈췄으면 거부 대신 보류 (PAUSE_ACTION_CLIENT_WRITE)
            if (reply == null && writesPaused && command.writes()) {
                return POSTPONED;
            }
        }
        if (reply == null) {
            // call() 의 enterExecutionUnit - 명령 하나 동안 만료 판정 시각 고정 (EXEC 안이면 단위 시작 시각 그대로)
            if (unit == null) {
                context.keyspace().updateCommandTime();
            }
            context.propagation().begin(argv);
            try {
                reply = command.execute(context, args);
            } catch (RuntimeException e) {
                // 명령 버그는 에러 응답 처리 - 실행 스레드 유지
                log("명령 %s 처리 중 예외: %s", command.name(), e);
                reply = Errors.internal(e.getClass().getSimpleName());
            }
            // BGSAVE·BGREWRITEAOF 로 자식 작업이 시작됐을 수 있음 - 다음 명령부터 반영
            updateDictResizePolicy();
            // 예외로 끝나도 이미 바꾼 만큼은 기록
            persistence.afterCommand(context.propagation().dirtyCount());
            if (unit != null) {
                unit.addAll(context.propagation().finish());
            } else {
                propagate(context.propagation().finish());
            }
        }

        // 레플리카가 보내는 1초 ACK 는 스트림에서 뺌 - 복제 패널의 ACK 시각으로 보임
        if (events.enabled() && !client.isReplica()) {
            events.publish(Event.command(connectionId, command.name(), args, System.nanoTime() - startNanos, reply));
        }
        return reply;
    }

    /**
     * updateDictResizePolicy - 자식 작업(BGSAVE·AOF 재작성·디스크리스 전송) 중이면 AVOID
     * Redis 는 fork 시작·종료 때 호출, 여기선 실행 스레드가 작업·명령마다 확인
     */
    private void updateDictResizePolicy() {
        Dict.setResizePolicy(persistence.childActive() ? Dict.ResizePolicy.AVOID : Dict.ResizePolicy.ENABLE);
    }

    private static void log(String format, Object... args) {
        System.out.println("[glass-redis] " + String.format(format, args));
    }
}
