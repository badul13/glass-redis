package glassredis.server;

import glassredis.command.Command;
import glassredis.command.Errors;
import glassredis.resp.RespValue;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;

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
 *   커넥션 스레드 ─┘
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

    /**
     * 크기 제한이 없는 큐를 쓴다. 제한이 없어도 무한정 쌓이지는 않는다 —
     * 커넥션은 응답을 받기 전까지 다음 명령을 넣지 않으므로,
     * 큐에 동시에 들어 있는 명령은 많아야 커넥션 수만큼이다.
     */
    private final BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();

    private final Thread thread = Thread.ofPlatform().name("glass-redis-executor").unstarted(this::runLoop);

    /** 실행 스레드가 더 이상 돌 수 없게 됐을 때 부른다. 서버는 여기에 자기 종료를 걸어둔다. */
    private final Runnable onFatalError;

    private volatile boolean running;

    CommandLoop(Runnable onFatalError) {
        this.onFatalError = onFatalError;
    }

    void start() {
        running = true;
        thread.start();
    }

    /**
     * 명령을 큐에 넣고 즉시 반환한다. 실행 결과는 돌려준 future 에 채워진다.
     *
     * <p>호출한 가상 스레드가 {@code get()} 으로 기다리는 동안에는 바탕의 OS 스레드를 반납한다.
     * 그래서 커넥션 수천 개가 동시에 기다리고 있어도 OS 스레드는 몇 개 쓰지 않는다.
     */
    CompletableFuture<RespValue> submit(Command command, List<byte[]> args) {
        CompletableFuture<RespValue> reply = new CompletableFuture<>();
        queue.add(() -> reply.complete(execute(command, args)));
        return reply;
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
        // take() 에서 기다리는 중이라면 인터럽트로 깨운다.
        thread.interrupt();
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
                // RuntimeException 은 execute() 가 이미 응답으로 바꿨으므로, 여기까지 오는 건 Error 다.
                // StackOverflowError, OutOfMemoryError 뒤에는 JVM 이나 데이터 상태를 믿을 수 없어서 계속 돌지 않는다.
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

    private static RespValue execute(Command command, List<byte[]> args) {
        try {
            return command.execute(args);
        } catch (RuntimeException e) {
            // 명령 구현의 버그는 그 명령의 에러 응답으로 끝낸다. 실행 스레드는 계속 돈다.
            log("명령 %s 처리 중 예외: %s", command.name(), e);
            return Errors.internal(e.getClass().getSimpleName());
        }
    }

    private static void log(String format, Object... args) {
        System.out.println("[glass-redis] " + String.format(format, args));
    }
}
