package glassredis.server;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.command.Propagation;
import glassredis.command.impl.PingCommand;
import glassredis.observe.AofSnapshot;
import glassredis.observe.Event;
import glassredis.observe.EventBus;
import glassredis.persistence.AppendOnlyLog;
import glassredis.persistence.Snapshot;
import glassredis.resp.RespValue;
import glassredis.store.ActiveExpireCycle;
import glassredis.store.Keyspace;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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

    /** 무제한 큐 - 커넥션당 명령 1개 + 주기 작업 1개라 최대 커넥션 수 + 1 */
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
        this.aof = aof;
        this.persistence = new Persistence(keyspace, aof, snapshot);
        this.context = new Context(keyspace, new Propagation(), persistence);
        this.events = events;
        // 만료 삭제는 DEL로 전파 - 명령 중이면 그 명령과 묶고, 주기적 만료면 단독
        keyspace.setExpiredListener(key -> {
            List<byte[]> del = List.of("DEL".getBytes(StandardCharsets.US_ASCII), key.bytes());
            if (context.propagation().active()) {
                context.propagation().also(del);
            } else {
                aof.feedAll(List.of(del));
            }
        });
        this.activeExpire = new ActiveExpireCycle(keyspace, events);
        this.onFatalError = onFatalError;
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
        CompletableFuture<RespValue> reply = new CompletableFuture<>();
        queue.add(() -> {
            RespValue result = execute(command, argv, connectionId);
            pendingReplies.add(() -> reply.complete(result));
        });
        return reply;
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
        activeExpire.run(ActiveExpireCycle.Kind.SLOW);
        keyspace.tryResizeHashTables();
        keyspace.incrementallyRehash();
        persistence.cron();
    }

    /**
     * 큐가 비어 쉬기 직전 - Redis beforeSleep 순서
     * FAST 만료(DEL 전파 생길 수 있음) → AOF write → 응답 전달
     */
    private void beforeSleep() {
        activeExpire.run(ActiveExpireCycle.Kind.FAST);
        aof.flush();
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

    /** call() - 실행 후 dirty면 AOF로 전파 */
    private RespValue execute(Command command, List<byte[]> argv, long connectionId) {
        // 관측 꺼짐 시 nanoTime() 비용도 생략
        long startNanos = events.enabled() ? System.nanoTime() : 0;
        List<byte[]> args = argv.subList(1, argv.size());

        // 저장 실패 중이면 쓰기 명령·PING 거부 (processCommand)
        RespValue reply = command.writes() || command instanceof PingCommand ? persistence.denyWrites() : null;
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
            aof.feedAll(context.propagation().finish());
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
