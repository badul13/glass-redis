package glassredis.server;

import glassredis.command.CommandRegistry;
import glassredis.observe.EventBus;
import glassredis.observe.KeyspaceSnapshot;
import glassredis.observe.SortedSetSnapshot;
import glassredis.store.Key;
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
import java.util.function.Function;

/** 리스닝 소켓 + 커넥션당 가상 스레드 */
public final class RedisServer implements AutoCloseable {

    /** tcp-backlog 기본값 */
    private static final int BACKLOG = 511;

    private final String bindAddress;
    private final int requestedPort;
    private final CommandRegistry registry;

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

    /** 즉시 반환 */
    public void start() throws IOException {
        serverSocket = new ServerSocket();
        // 재시작 시 TIME_WAIT 주소 재사용
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(bindAddress, requestedPort), BACKLOG);

        running = true;
        commandLoop = new CommandLoop(new Keyspace(events), events, this::close);
        commandLoop.start();
        connectionExecutor = Executors.newVirtualThreadPerTaskExecutor();
        Thread.ofPlatform().name("glass-redis-acceptor").start(this::acceptLoop);
    }

    /** 포트 0으로 열었으면 OS가 고른 포트 */
    public int port() {
        return serverSocket.getLocalPort();
    }

    /** 실행 스레드에서 스냅샷 - 서버 종료 중이거나 1초 내 미수신 시 null */
    public KeyspaceSnapshot keyspaceSnapshot() {
        return inspect(keyspace -> KeyspaceSnapshot.of(keyspace, KeyspaceSnapshot.DEFAULT_MAX_KEYS));
    }

    /** keyspaceSnapshot()과 같은 방식 */
    public SortedSetSnapshot sortedSetSnapshot(byte[] key) {
        return inspect(keyspace -> SortedSetSnapshot.of(keyspace, new Key(key), SortedSetSnapshot.DEFAULT_MAX_NODES));
    }

    private <T> T inspect(Function<Keyspace, T> reader) {
        try {
            return commandLoop.inspect(reader).get(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException | TimeoutException e) {
            return null;
        }
    }

    public void awaitStop() throws InterruptedException {
        stopped.await();
    }

    @Override
    public void close() {
        running = false;
        try {
            if (serverSocket != null) {
                // accept() 깨우기
                serverSocket.close();
            }
        } catch (IOException ignored) {
            // 종료 중 - 알릴 곳 없음
        }
        if (connectionExecutor != null) {
            // 응답 대기 중 커넥션 스레드도 이 인터럽트로 해제
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
        } finally {
            stopped.countDown();
        }
    }
}
