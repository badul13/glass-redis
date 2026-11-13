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
import glassredis.observe.Event;
import glassredis.observe.EventBus;
import glassredis.persistence.AppendOnlyLog;
import glassredis.persistence.RdbLoader;
import glassredis.persistence.Snapshot;
import glassredis.replication.Replicator;
import glassredis.resp.RespValue;
import glassredis.store.ActiveExpireCycle;
import glassredis.store.Keyspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
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

    /** Redis hz 기본값 */
    private static final int HZ = ActiveExpireCycle.HZ;

    private static final byte[] MULTI = "MULTI".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] EXEC = "EXEC".getBytes(StandardCharsets.US_ASCII);

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
        this.activeExpire = new ActiveExpireCycle(keyspace, events);
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
            log("MASTER <-> REPLICA 동기화: 메모리로 로드합니다");
            RdbLoader.Result loaded = snapshot != null
                    ? snapshot.load(keyspace, RdbLoader.Options.REPLICA)
                    : RdbLoader.load(target, keyspace, RdbLoader.Options.REPLICA);
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
        CompletableFuture<List<RespValue>> replies = new CompletableFuture<>();
        queue.add(() -> {
            // 묶음 시작 시 보낼 응답 없음 - 앞 묶음은 이미 flush 됨
            client.pendingReplies(false);
            List<RespValue> results = new ArrayList<>(requests.size());
            for (Request request : requests) {
                RespValue reply = request.command() == null
                        ? Errors.unknownCommand(request.argv().get(0),
                                request.argv().subList(1, request.argv().size()))
                        : execute(request.command(), request.argv(), client);
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
            pendingReplies.add(() -> replies.complete(results));
        });
        return replies;
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

    private void stop(boolean save) {
        running = false;
        cronTimer.shutdownNow();
        thread.interrupt();
        if (thread != Thread.currentThread()) {
            try {
                thread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        master.close();
        if (save && !fatal) {
            persistence.shutdown();
        } else {
            persistence.close();
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
        if (!master.isReplica()) {
            activeExpire.run(ActiveExpireCycle.Kind.SLOW);
        }
        keyspace.tryResizeHashTables();
        keyspace.incrementallyRehash();
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
        if (!master.isReplica()) {
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
        while (running) {
            try {
                // 큐가 비면 곧 대기 - beforeSleep
                if (queue.isEmpty()) {
                    beforeSleep();
                }
                queue.take().run();
            } catch (InterruptedException e) {
                return; // close()
            } catch (Throwable fatal) {
                // Error 또는 내부 작업 예외 - 상태 신뢰 불가
                // 이 스레드만 죽으면 모든 클라이언트 무한 대기 - 서버 전체 종료
                System.err.println("[glass-redis] 실행 스레드에서 치명적 오류가 발생해 서버를 종료합니다");
                fatal.printStackTrace();
                this.fatal = true;
                onFatalError.run();
                return;
            }
        }
    }

    /**
     * propagatePendingCommands - 둘 이상이면 MULTI/EXEC 로 감쌈
     * AOF 도 복제도 꺼져 있으면 아무것도 안 함 (shouldPropagate)
     */
    private void propagate(List<List<byte[]>> commands) {
        if (commands.isEmpty() || (!aof.enabled() && !master.propagating())) {
            return;
        }
        boolean transaction = commands.size() > 1;
        if (transaction) {
            propagateNow(-1, List.of(MULTI));
        }
        for (List<byte[]> argv : commands) {
            propagateNow(0, argv);
        }
        if (transaction) {
            propagateNow(-1, List.of(EXEC));
        }
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
        int arity = command.arity();
        RespValue reply;
        if ((arity > 0 && argv.size() != arity) || argv.size() < -arity) {
            reply = Errors.wrongNumberOfArguments(command.name());
        } else if (client.isMaster()) {
            // 마스터가 보낸 건 따름 (mustObeyClient) - MISCONF·READONLY 없음
            reply = null;
        } else {
            reply = command.writes() || command instanceof PingCommand ? persistence.denyWrites() : null;
            if (reply == null && command.writes() && master.deniesWrite(client)) {
                reply = READONLY;
            }
        }
        if (reply == null) {
            context.propagation().begin(argv);
            try {
                reply = command.execute(context, args);
            } catch (RuntimeException e) {
                // 명령 버그는 에러 응답 처리 - 실행 스레드 유지
                log("명령 %s 처리 중 예외: %s", command.name(), e);
                reply = Errors.internal(e.getClass().getSimpleName());
            }
            // 예외로 끝나도 이미 바꾼 만큼은 기록
            persistence.afterCommand(context.propagation().dirtyCount());
            if (unit != null) {
                unit.addAll(context.propagation().finish());
            } else {
                propagate(context.propagation().finish());
            }
        }

        if (events.enabled()) {
            events.publish(Event.command(connectionId, command.name(), args, System.nanoTime() - startNanos, reply));
        }
        return reply;
    }

    private static void log(String format, Object... args) {
        System.out.println("[glass-redis] " + String.format(format, args));
    }
}
