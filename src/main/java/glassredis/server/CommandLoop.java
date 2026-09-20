package glassredis.server;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.observe.Event;
import glassredis.observe.EventBus;
import glassredis.observe.KeyspaceSnapshot;
import glassredis.resp.RespValue;
import glassredis.store.ExpiryCycle;
import glassredis.store.Keyspace;

import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 모든 명령을 스레드 하나에서 차례로 실행한다.
 *
 * <p>커넥션 스레드는 수천 개일 수 있지만 명령을 실행하는 스레드는 이 하나뿐이다.
 * 커넥션 스레드는 명령을 큐에 넣고 답이 채워질 때까지 기다리고,
 * 실행 스레드는 큐에서 하나씩 꺼내 실행한다.
 *
 * <pre>
 *   커넥션 스레드 ─┐
 *   커넥션 스레드 ─┼─▶ 큐 ─▶ 실행 스레드 ─▶ Command.execute()
 *   커넥션 스레드 ─┤
 *   만료 타이머  ──┘  (100ms 마다 샘플링 작업을 넣음)
 * </pre>
 *
 * <p>이렇게 하면 데이터를 만지는 스레드가 하나로 고정된다. 락 없이 평범한 {@code HashMap} 을 써도 되고,
 * "읽고, 고치고, 쓰는" 명령({@code INCR} 등)의 중간에 다른 명령이 끼어들 틈이 없다.
 * 실제 Redis 도 명령 실행은 스레드 하나에서 한다.
 *
 * <p>소켓 입출력은 여기서 하지 않는다. 느린 클라이언트 하나에 쓰다가 막히면
 * 뒤에 줄 선 모든 명령이 같이 멈추기 때문이다. 응답을 쓰는 건 각 커넥션 스레드의 몫이다.
 *
 * <p>실행 스레드는 가상 스레드가 아니라 플랫폼 스레드다. 가상 스레드는 기다리는 일이 많을 때
 * OS 스레드를 반납해서 이득을 보는데, 이 스레드는 쉬지 않고 계산만 하므로 얻을 게 없다.
 */
final class CommandLoop implements AutoCloseable {

    /** 주기적 만료 샘플링을 1초에 몇 번 돌릴지. 실제 Redis 설정 {@code hz} 의 기본값과 같다. */
    private static final int EXPIRY_HZ = 10;

    /**
     * 크기 제한이 없는 큐를 쓴다. 제한이 없어도 무한정 쌓이지는 않는다 —
     * 커넥션은 응답을 받기 전까지 다음 명령을 넣지 않고, 샘플링 작업은 한 번에 하나만 넣는다.
     * 그래서 큐에 동시에 들어 있는 작업은 많아야 "커넥션 수 + 1" 개다.
     */
    private final BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();

    private final Thread thread = Thread.ofPlatform().name("glass-redis-executor").unstarted(this::runLoop);

    /**
     * 샘플링 주기를 재는 타이머. 시간만 재고 샘플링 작업은 큐에 넣기만 한다.
     * 타이머 스레드가 키스페이스를 직접 만지면 "실행 스레드만 만진다"는 전제가 깨진다.
     */
    private final ScheduledExecutorService expiryTimer = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("glass-redis-expiry-timer").daemon(true).factory());

    /** 샘플링 작업이 이미 큐에서 기다리는 중인지. 실행 스레드가 밀려 있을 때 같은 작업이 줄줄이 쌓이지 않게 한다. */
    private final AtomicBoolean expiryQueued = new AtomicBoolean();

    /** 명령에 넘기는 서버 상태. 안에 든 키스페이스는 이 클래스의 실행 스레드만 만진다. */
    private final Context context;

    private final ExpiryCycle expiryCycle;

    /** 실행한 명령을 알릴 곳. 관측이 꺼져 있으면 시간을 재지도 않는다. */
    private final EventBus events;

    /** 실행 스레드가 더 이상 돌 수 없게 됐을 때 부른다. 서버는 여기에 자기 종료를 걸어둔다. */
    private final Runnable onFatalError;

    private volatile boolean running;

    /**
     * @param keyspace 넘긴 뒤로는 실행 스레드만 만져야 한다. {@link #start()} 전에 채워 넣는 것까지는 괜찮다.
     */
    CommandLoop(Keyspace keyspace, EventBus events, Runnable onFatalError) {
        this.context = new Context(keyspace);
        this.events = events;
        // RandomGenerator.getDefault() 는 쓰지 않는다. 그 구현(L32X64MixRandom)은 jdk.random 모듈에 있어서,
        // 모듈을 덜어낸 JRE 이미지에서는 서버가 시작하자마자 죽는다. SplittableRandom 은 java.base 에 있다.
        this.expiryCycle = new ExpiryCycle(keyspace, new SplittableRandom(), ExpiryCycle.DEFAULT_TIME_BUDGET, events);
        this.onFatalError = onFatalError;
    }

    void start() {
        running = true;
        thread.start();
        long periodMillis = 1000 / EXPIRY_HZ;
        expiryTimer.scheduleAtFixedRate(this::requestExpiryCycle, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * 명령을 큐에 넣고 즉시 반환한다. 실행 결과는 돌려준 future 에 채워진다.
     *
     * <p>호출한 가상 스레드가 {@code get()} 으로 기다리는 동안에는 바탕의 OS 스레드를 반납한다.
     * 그래서 커넥션 수천 개가 동시에 기다리고 있어도 OS 스레드는 몇 개 쓰지 않는다.
     *
     * @param connectionId 이 명령을 보낸 커넥션. 실행 자체에는 쓰이지 않고, 대시보드의 명령 스트림에서
     *                     누가 보낸 명령인지를 보여주는 데만 쓴다.
     */
    CompletableFuture<RespValue> submit(Command command, List<byte[]> args, long connectionId) {
        CompletableFuture<RespValue> reply = new CompletableFuture<>();
        queue.add(() -> reply.complete(execute(command, args, connectionId)));
        return reply;
    }

    /**
     * 지금 이 순간의 키스페이스를 뜬다.
     *
     * <p>대시보드가 키 목록을 그리려면 키스페이스를 읽어야 하는데, 그건 실행 스레드만 할 수 있는 일이다.
     * 그래서 HTTP 스레드가 직접 읽지 않고 여기에 작업을 맡긴다. 명령과 같은 큐에 줄을 서므로
     * 명령이 반쯤 실행된 중간 상태가 찍히는 일도 없다.
     *
     * <p>이걸 {@code KEYS} 같은 진짜 명령으로 만들지 않은 이유는, redis-cli 에 내보낼 것도 아니고
     * 응답이 RESP 여야 할 이유도 없어서다.
     */
    CompletableFuture<KeyspaceSnapshot> snapshot(int maxKeys) {
        CompletableFuture<KeyspaceSnapshot> result = new CompletableFuture<>();
        queue.add(() -> result.complete(KeyspaceSnapshot.of(context.keyspace(), maxKeys)));
        return result;
    }

    /**
     * 실행 스레드를 멈춘다.
     *
     * <p>큐에 남은 명령의 future 는 채워지지 않는다. 그걸 기다리던 커넥션 스레드는
     * {@link RedisServer#close()} 가 커넥션 스레드들을 인터럽트할 때 풀려난다.
     */
    @Override
    public void close() {
        running = false;
        expiryTimer.shutdownNow();
        // take() 에서 기다리는 중이라면 인터럽트로 깨운다.
        thread.interrupt();
    }

    /** 타이머 스레드에서 불린다. */
    private void requestExpiryCycle() {
        if (expiryQueued.compareAndSet(false, true)) {
            queue.add(() -> {
                expiryQueued.set(false);
                expiryCycle.run();
            });
        }
    }

    private void runLoop() {
        while (running) {
            Runnable task;
            try {
                task = queue.take();
            } catch (InterruptedException e) {
                return; // close() 가 깨웠다
            }
            try {
                task.run();
            } catch (Throwable fatal) {
                // 명령의 RuntimeException 은 execute() 가 이미 응답으로 바꿨다. 여기까지 오는 건 Error 이거나,
                // 명령이 아닌 내부 작업(만료 샘플링)이 던진 예외다. 어느 쪽이든 JVM 이나 데이터 상태를 믿을 수 없다.
                //
                // 그렇다고 이 스레드만 조용히 끝나면 더 나쁘다. 큐를 꺼낼 스레드가 없어져서
                // 모든 클라이언트가 답을 영원히 기다리는데, 프로세스와 포트는 살아 있어 밖에서 알아채기 어렵다.
                // 그래서 서버 전체를 내린다. 실제 Redis 도 내부 버그를 만나면 버그 리포트를 남기고 종료한다.
                System.err.println("[glass-redis] 실행 스레드에서 치명적 오류가 발생해 서버를 종료합니다");
                fatal.printStackTrace();
                onFatalError.run();
                return;
            }
        }
    }

    private RespValue execute(Command command, List<byte[]> args, long connectionId) {
        // 관측이 꺼져 있으면 시계를 읽지 않는다. System.nanoTime() 은 공짜가 아니라서,
        // 명령 하나가 수백 ns 로 끝나는 구간에서는 이것만으로도 측정값이 흔들린다.
        long startNanos = events.enabled() ? System.nanoTime() : 0;

        RespValue reply;
        try {
            reply = command.execute(context, args);
        } catch (RuntimeException e) {
            // 명령 구현의 버그는 그 명령의 에러 응답으로 끝낸다. 실행 스레드는 계속 돈다.
            log("명령 %s 처리 중 예외: %s", command.name(), e);
            reply = Errors.internal(e.getClass().getSimpleName());
        }

        if (events.enabled()) {
            // 실패한 명령도 그대로 알린다. 무엇이 왜 실패했는지가 화면에서 제일 보고 싶은 것 중 하나다.
            events.publish(Event.command(connectionId, command.name(), args, System.nanoTime() - startNanos, reply));
        }
        return reply;
    }

    private static void log(String format, Object... args) {
        System.out.println("[glass-redis] " + String.format(format, args));
    }
}
