package glassredis.server;

import glassredis.command.CommandRegistry;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    private final AtomicLong nextConnectionId = new AtomicLong(1);
    private final CountDownLatch stopped = new CountDownLatch(1);

    private volatile boolean running;
    private ServerSocket serverSocket;
    private ExecutorService connectionExecutor;

    public RedisServer(String bindAddress, int port, CommandRegistry registry) {
        this.bindAddress = bindAddress;
        this.requestedPort = port;
        this.registry = registry;
    }

    /** 소켓을 열고 accept 루프를 별도 스레드에서 시작한다. 즉시 반환한다. */
    public void start() throws IOException {
        serverSocket = new ServerSocket();
        // 서버를 껐다 바로 켤 때 TIME_WAIT 상태의 주소를 재사용할 수 있게 한다.
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(bindAddress, requestedPort), BACKLOG);

        running = true;
        connectionExecutor = Executors.newVirtualThreadPerTaskExecutor();
        Thread.ofPlatform().name("glass-redis-acceptor").start(this::acceptLoop);
    }

    /** 실제로 열린 포트. 생성자에 0 을 주면 OS 가 빈 포트를 골라주므로 테스트에서 유용하다. */
    public int port() {
        return serverSocket.getLocalPort();
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
            connectionExecutor.shutdownNow();
        }
    }

    private void acceptLoop() {
        try {
            while (running) {
                Socket socket = serverSocket.accept();
                long id = nextConnectionId.getAndIncrement();
                connectionExecutor.submit(new Connection(socket, registry, id));
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
