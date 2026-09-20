package glassredis.server;

import glassredis.command.CommandRegistry;
import glassredis.observe.EventBus;
import glassredis.observe.KeyspaceSnapshot;
import glassredis.store.Keyspace;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 리스닝 소켓을 열고 들어오는 커넥션을 받아 가상 스레드에 넘긴다.
 *
 * <p>서버 소켓 하나가 "이 포트로 오는 접속 요청을 받겠다"는 선언이고,
 * {@code accept()} 가 반환하는 소켓 하나하나가 실제 대화 통로다.
 * 이 둘은 다른 것이다 — 서버 소켓으로는 데이터를 주고받지 않는다.
 */
public final class RedisServer implements AutoCloseable {

    /** 접속 대기 큐 길이. 실제 Redis 의 tcp-backlog 기본값과 같다. */
    private static final int BACKLOG = 511;

    private final String bindAddress;
    private final int requestedPort;
    private final CommandRegistry registry;

    /** 관측을 끄면 {@link EventBus#NONE} 이라 서버 코드에는 아무 비용도 남지 않는다. */
    private final EventBus events;

    private final AtomicLong nextConnectionId = new AtomicLong(1);
    private final CountDownLatch stopped = new CountDownLatch(1);

    private volatile boolean running;
    private ServerSocket serverSocket;
    private ExecutorService connectionExecutor;
    private CommandLoop commandLoop;

    public RedisServer(String bindAddress, int port, CommandRegistry registry) {
        this(bindAddress, port, registry, EventBus.NONE);
    }

    public RedisServer(String bindAddress, int port, CommandRegistry registry, EventBus events) {
        this.bindAddress = bindAddress;
        this.requestedPort = port;
        this.registry = registry;
        this.events = events;
    }

    /** 소켓을 열고 accept 루프를 별도 스레드에서 시작한다. 즉시 반환한다. */
    public void start() throws IOException {
        serverSocket = new ServerSocket();
        // 서버를 껐다 바로 켤 때 TIME_WAIT 상태의 주소를 재사용할 수 있게 한다.
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(bindAddress, requestedPort), BACKLOG);

        running = true;
        commandLoop = new CommandLoop(new Keyspace(events), events, this::close);
        commandLoop.start();
        connectionExecutor = Executors.newVirtualThreadPerTaskExecutor();
        Thread.ofPlatform().name("glass-redis-acceptor").start(this::acceptLoop);
    }

    /** 실제로 열린 포트. 생성자에 0 을 주면 OS 가 빈 포트를 골라주므로 테스트에서 유용하다. */
    public int port() {
        return serverSocket.getLocalPort();
    }

    /**
     * 대시보드가 그릴 키 목록. 실행 스레드에 부탁해서 받아 온다.
     *
     * <p>서버가 멈추는 중이면 그 부탁에 답할 스레드가 없다. 그때는 기다리지 않고 {@code null} 을 준다 —
     * 대시보드는 이번 차례를 건너뛰면 그만이고, 화면 하나 때문에 종료가 늦어질 이유는 없다.
     */
    public KeyspaceSnapshot keyspaceSnapshot() {
        try {
            return commandLoop.snapshot(KeyspaceSnapshot.DEFAULT_MAX_KEYS).get(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException | TimeoutException e) {
            return null;
        }
    }

    /** 서버가 멈출 때까지 블로킹한다. */
    public void awaitStop() throws InterruptedException {
        stopped.await();
    }

    @Override
    public void close() {
        running = false;
        try {
            if (serverSocket != null) {
                // accept() 에서 블로킹 중인 스레드를 깨우는 방법은 소켓을 닫는 것이다.
                serverSocket.close();
            }
        } catch (IOException ignored) {
            // 종료 중의 실패는 알릴 상대가 없다
        }
        if (connectionExecutor != null) {
            // 실행 스레드의 답을 기다리던 커넥션 스레드도 이 인터럽트로 풀려난다.
            connectionExecutor.shutdownNow();
        }
        if (commandLoop != null) {
            commandLoop.close();
        }
    }

    private void acceptLoop() {
        try {
            while (running) {
                Socket socket = serverSocket.accept();
                long id = nextConnectionId.getAndIncrement();
                connectionExecutor.submit(new Connection(socket, registry, commandLoop, id, events));
            }
        } catch (IOException e) {
            if (running) {
                System.err.println("[glass-redis] accept 루프가 중단되었습니다: " + e.getMessage());
            }
            // running == false 면 close() 가 소켓을 닫아서 나온 것이므로 정상 종료다.
        } finally {
            stopped.countDown();
        }
    }
}
