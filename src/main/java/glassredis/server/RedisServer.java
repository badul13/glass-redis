package glassredis.server;

import glassredis.command.CommandRegistry;
import glassredis.observe.AofSnapshot;
import glassredis.observe.EventBus;
import glassredis.observe.KeyspaceSnapshot;
import glassredis.observe.RdbSnapshot;
import glassredis.observe.ReplicationSnapshot;
import glassredis.observe.SortedSetSnapshot;
import glassredis.persistence.AppendOnlyLog;
import glassredis.persistence.RdbLoader;
import glassredis.persistence.Snapshot;
import glassredis.replication.Replicator;
import glassredis.store.Key;
import glassredis.store.Keyspace;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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

    /** null이면 기본 설정(appendonly no)으로 생성 */
    private AppendOnlyLog aof;

    /** null이면 RDB 없음 - 기동 로드 생략, SAVE 실패 (현재 폴더 dump.rdb 를 테스트가 읽지 않도록) */
    private final Snapshot snapshot;

    private final Replicator.Config replication;

    private final AtomicLong nextConnectionId = new AtomicLong(1);
    private final CountDownLatch stopped = new CountDownLatch(1);

    private volatile boolean running;
    /** 프로세스 종료 - 테스트에서 교체 */
    private java.util.function.IntConsumer exit = System::exit;

    /** 테스트용 - 신호 종료가 끝났을 때 System.exit 대신 */
    void exitWith(java.util.function.IntConsumer action) {
        this.exit = action;
    }

    /** shutdown-timeout - start() 전에 설정 */
    private long shutdownTimeoutSeconds = 10;

    /** shutdown-timeout (초) - 종료 때 뒤처진 레플리카를 기다리는 한도, 0 은 안 기다림 */
    public void shutdownTimeout(long seconds) {
        this.shutdownTimeoutSeconds = seconds;
    }

    /**
     * SIGTERM·SIGINT - 플래그만, 실제 종료는 실행 스레드의 cron (저장 실패면 계속 돎)
     *
     * @return 종료 중 두 번째 SIGINT - 호출한 쪽이 저장 없이 즉시 종료
     */
    public boolean requestShutdown(boolean sigint) {
        return commandLoop != null && commandLoop.requestShutdown(sigint);
    }
    private ServerSocket serverSocket;
    private ExecutorService connectionExecutor;
    private CommandLoop commandLoop;

    public RedisServer(String bindAddress, int port, CommandRegistry registry) {
        this(bindAddress, port, registry, EventBus.NONE);
    }

    public RedisServer(String bindAddress, int port, CommandRegistry registry, EventBus events) {
        this(bindAddress, port, registry, events, null);
    }

    public RedisServer(String bindAddress, int port, CommandRegistry registry, EventBus events, AppendOnlyLog aof) {
        this(bindAddress, port, registry, events, aof, null);
    }

    public RedisServer(String bindAddress, int port, CommandRegistry registry, EventBus events, AppendOnlyLog aof,
                       Snapshot snapshot) {
        this(bindAddress, port, registry, events, aof, snapshot, Replicator.Config.defaults());
    }

    /** @param replication 복제 설정 (repl-*) */
    public RedisServer(String bindAddress, int port, CommandRegistry registry, EventBus events, AppendOnlyLog aof,
                       Snapshot snapshot, Replicator.Config replication) {
        this.replication = replication;
        this.bindAddress = bindAddress;
        this.requestedPort = port;
        this.registry = registry;
        this.events = events;
        this.aof = aof;
        this.snapshot = snapshot;
    }

    /** replicaof 설정 - start() 전, 기동하면 이 마스터에 붙음 */
    private String masterHost;
    private int masterPort;

    public RedisServer replicaOf(String host, int port) {
        this.masterHost = host;
        this.masterPort = port;
        return this;
    }

    /**
     * 즉시 반환 - 복구는 소켓 열기 전에 끝냄
     * AOF 켜져 있으면 AOF만, 아니면 RDB (loadDataFromDisk)
     *
     * @throws IOException 복구 실패 - 기동 중단
     */
    public void start() throws IOException {
        Keyspace keyspace = new Keyspace(events);
        if (aof == null) {
            aof = new AppendOnlyLog(AppendOnlyLog.Config.defaults(Path.of(".")), events);
        }
        // initServer 가 소켓을 먼저 열고 loadDataFromDisk - 로딩 중 접속은 -LOADING (processEventsWhileBlocked)
        serverSocket = new ServerSocket();
        // 재시작 시 TIME_WAIT 주소 재사용
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(bindAddress, requestedPort), BACKLOG);

        running = true;
        commandLoop = new CommandLoop(keyspace, events, aof, snapshot, replication, this::close);
        commandLoop.registry(registry);
        commandLoop.port(serverSocket.getLocalPort());
        commandLoop.shutdownTimeout(shutdownTimeoutSeconds);
        // 신호로 시작한 종료가 저장까지 끝남 - 저장 없이 정리 후 프로세스 종료 (exit 0)
        commandLoop.onShutdownFinished(() -> {
            close();
            Thread.ofPlatform().name("glass-redis-exit").start(() -> exit.accept(0));
        });
        // 접속 받기 전에 로딩 시작 - 첫 접속부터 -LOADING
        commandLoop.beginLoading(masterHost != null && replication.replicaReadOnly());
        connectionExecutor = Executors.newVirtualThreadPerTaskExecutor();
        Thread.ofPlatform().name("glass-redis-acceptor").start(this::acceptLoop);

        RdbLoader.Result loaded = null;
        // 마스터가 로드하며 버린 지난 만료 키 - 백로그에 DEL 로 (RDBFLAGS_FEED_REPL)
        List<byte[]> expiredKeys = new ArrayList<>();
        try {
            // 로더가 2MB·1024 명령마다 로딩 중 명령 처리 (이 스레드에서 - 실행 스레드는 아직)
            Runnable progress = commandLoop::processEventsWhileLoading;
            aof.start(keyspace, registry, progress);
            if (!aof.enabled() && snapshot != null) {
                loaded = snapshot.load(keyspace, (masterHost != null
                        ? RdbLoader.Options.REPLICA : new RdbLoader.Options(false, expiredKeys::add)).withProgress(progress));
                if (loaded.status() == RdbLoader.Status.FAILED) {
                    throw new IOException("RDB 복구 실패: " + loaded.error());
                }
            }
        } catch (IOException e) {
            // 복구 실패 - 기동 중단 (Redis 는 exit 1)
            close();
            throw e;
        }

        if (masterHost != null) {
            commandLoop.replicator().startAsReplica(masterHost, masterPort, loaded);
        } else if (!aof.enabled() && snapshot != null) {
            commandLoop.replicator().afterMasterLoad(loaded, expiredKeys);
        }
        commandLoop.endLoading();
        commandLoop.start();
    }

    /** server.loading - 기동 복구 중이거나 레플리카 동기화 RDB 로드 중 */
    boolean loading() {
        return commandLoop != null && commandLoop.loading();
    }

    /** 포트 0으로 열었으면 OS가 고른 포트 */
    /** 복제 상태 - 테스트·대시보드 읽기용, 값은 실행 스레드가 바꿈 */
    public Replicator replicator() {
        return commandLoop.replicator();
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    /** 실행 스레드에서 스냅샷 - 서버 종료 중이거나 1초 내 미수신 시 null */
    public KeyspaceSnapshot keyspaceSnapshot() {
        return inspect(keyspace -> KeyspaceSnapshot.of(keyspace, KeyspaceSnapshot.DEFAULT_MAX_KEYS));
    }

    /** keyspaceSnapshot()과 같은 방식 */
    public AofSnapshot aofSnapshot() {
        try {
            return commandLoop.aofSnapshot().get(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException | TimeoutException e) {
            return null;
        }
    }

    /** keyspaceSnapshot()과 같은 방식 - RDB 없으면 null */
    public RdbSnapshot rdbSnapshot() {
        try {
            return commandLoop.rdbSnapshot().get(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException | TimeoutException e) {
            return null;
        }
    }

    /** keyspaceSnapshot()과 같은 방식 */
    public ReplicationSnapshot replicationSnapshot() {
        try {
            return commandLoop.replicationSnapshot().get(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException | TimeoutException e) {
            return null;
        }
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

    /** 정상 종료 - close() 에 더해 save 규칙 있으면 마지막 RDB 저장 (SIGTERM·SIGINT) */
    public void shutdown() {
        stop(true);
    }

    /** 저장 없이 멈춤 - 프로세스가 죽은 것과 같음 (AOF 버퍼는 마무리) */
    @Override
    public void close() {
        stop(false);
    }

    private void stop(boolean save) {
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
            if (save) {
                commandLoop.shutdown();
            } else {
                commandLoop.close();
            }
        }
    }

    private void acceptLoop() {
        try {
            while (running) {
                Socket socket = serverSocket.accept();
                long id = nextConnectionId.getAndIncrement();
                connectionExecutor.submit(new Connection(socket, registry, commandLoop, id, events, this::loading));
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
