package glassredis.server;

import glassredis.command.CommandRegistry;
import glassredis.observe.EventBus;
import glassredis.persistence.AppendOnlyLog;
import glassredis.persistence.Snapshot;
import glassredis.replication.Replicator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 신호 종료 - prepareForShutdown·finishShutdown·cancelShutdown 흐름 */
@Timeout(30)
class ShutdownTest {

    @TempDir
    Path dir;

    private final List<RedisServer> servers = new ArrayList<>();
    private final List<Socket> sockets = new ArrayList<>();

    @AfterEach
    void stopAll() throws IOException {
        for (Socket socket : sockets) {
            socket.close();
        }
        servers.forEach(RedisServer::close);
    }

    /** save 규칙 있는 마스터 - 디스크리스 지연 없음, 종료 결과는 exitCode 로 */
    private RedisServer start(String name, long shutdownTimeout, CompletableFuture<Integer> exitCode) throws IOException {
        Path home = Files.createDirectories(dir.resolve(name));
        Replicator.Config d = Replicator.Config.defaults();
        Replicator.Config config = new Replicator.Config(true, 0, 0, d.backlogSize(), d.backlogTtl(), 3600,
                60, d.outputHardLimit(), d.outputSoftLimit(), d.outputSoftSeconds());
        RedisServer server = new RedisServer("127.0.0.1", 0, CommandRegistry.withBuiltins(), EventBus.NONE,
                new AppendOnlyLog(AppendOnlyLog.Config.defaults(home), EventBus.NONE),
                new Snapshot(new Snapshot.Config(home, "dump.rdb", Snapshot.Config.parseSave("3600 1"), true)),
                config);
        server.shutdownTimeout(shutdownTimeout);
        server.exitWith(exitCode::complete);
        server.start();
        servers.add(server);
        return server;
    }

    /** ACK 를 안 보내는 레플리카 - capa eof(디스크리스) 로 PSYNC 만 하고 침묵, 마스터 기준 뒤처짐 */
    private void silentReplica(int masterPort) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", masterPort), 3000);
        sockets.add(socket);
        OutputStream out = socket.getOutputStream();
        // 실제 레플리카처럼 +OK 받은 뒤 PSYNC
        out.write("*3\r\n$8\r\nREPLCONF\r\n$4\r\ncapa\r\n$3\r\neof\r\n".getBytes(StandardCharsets.US_ASCII));
        out.flush();
        byte[] ok = socket.getInputStream().readNBytes(5);
        assertEquals("+OK\r\n", new String(ok, StandardCharsets.US_ASCII));
        out.write("*3\r\n$5\r\nPSYNC\r\n$1\r\n?\r\n$2\r\n-1\r\n".getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    @Test
    @DisplayName("SIGTERM - 마지막 RDB 저장 후 정리, exit 0")
    void signalSavesAndExits() throws Exception {
        CompletableFuture<Integer> exitCode = new CompletableFuture<>();
        RedisServer server = start("ok", 10, exitCode);
        try (Client c = new Client(server.port())) {
            c.call("SET", "k", "v");
        }
        assertEquals(false, server.requestShutdown(false));
        assertEquals(0, exitCode.get(5, TimeUnit.SECONDS));
        assertTrue(Files.size(dir.resolve("ok").resolve("dump.rdb")) > 0);
    }

    @Test
    @DisplayName("마지막 RDB 저장 실패 - 종료 취소, 계속 명령 받음 (can't exit)")
    void saveFailureCancelsShutdown() throws Exception {
        CompletableFuture<Integer> exitCode = new CompletableFuture<>();
        RedisServer server = start("fail", 10, exitCode);
        Files.createDirectories(dir.resolve("fail").resolve("dump.rdb").resolve("block"));
        server.requestShutdown(false);
        assertThrows(TimeoutException.class, () -> exitCode.get(1, TimeUnit.SECONDS));
        try (Client c = new Client(server.port())) {
            assertEquals("OK", c.call("SET", "still", "alive"));
        }
    }

    @Test
    @DisplayName("뒤처진 레플리카 - 쓰기 명령은 보류·읽기는 응답, shutdown-timeout 지나면 종료")
    void waitsForLaggingReplicaWithWritesPaused() throws Exception {
        CompletableFuture<Integer> exitCode = new CompletableFuture<>();
        RedisServer server = start("lag", 2, exitCode);
        silentReplica(server.port());
        try (Client c = new Client(server.port()); Client writer = new Client(server.port())) {
            c.call("SET", "k", "v");
            server.requestShutdown(false);
            Thread.sleep(300);
            assertEquals("v", c.call("GET", "k"), "읽기는 그대로");
            CompletableFuture<String> paused = CompletableFuture.supplyAsync(() -> {
                try {
                    return writer.call("SET", "k", "new");
                } catch (IOException e) {
                    return "끊김";
                }
            });
            assertThrows(TimeoutException.class, () -> paused.get(500, TimeUnit.MILLISECONDS), "쓰기는 보류");
            assertEquals(0, exitCode.get(10, TimeUnit.SECONDS), "shutdown-timeout 뒤 종료");
        }
    }

    @Test
    @DisplayName("대기 중 파이프라인 GET+SET - 보류 지점 앞 GET 응답은 바로, SET 은 보류")
    void repliesBeforePostponedWriteAreSent() throws Exception {
        CompletableFuture<Integer> exitCode = new CompletableFuture<>();
        RedisServer server = start("pipe", 5, exitCode);
        silentReplica(server.port());
        try (Client c = new Client(server.port())) {
            c.call("SET", "k", "v");
        }
        server.requestShutdown(false);
        Thread.sleep(300);
        try (Socket raw = new Socket()) {
            raw.connect(new InetSocketAddress("127.0.0.1", server.port()), 3000);
            raw.setSoTimeout(1000);
            raw.getOutputStream().write(("*2\r\n$3\r\nGET\r\n$1\r\nk\r\n*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$1\r\nx\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            raw.getOutputStream().flush();
            byte[] first = raw.getInputStream().readNBytes(7);
            assertEquals("$1\r\nv\r\n", new String(first, StandardCharsets.US_ASCII), "GET 응답은 보류 전에");
            assertThrows(java.net.SocketTimeoutException.class, () -> raw.getInputStream().read(), "SET 은 보류");
        }
    }

    @Test
    @DisplayName("레플리카 대기 끝에 저장 실패 - 종료 취소, 보류했던 쓰기 실행")
    void cancelledShutdownResumesPausedWrites() throws Exception {
        CompletableFuture<Integer> exitCode = new CompletableFuture<>();
        RedisServer server = start("resume", 1, exitCode);
        silentReplica(server.port());
        Files.createDirectories(dir.resolve("resume").resolve("dump.rdb").resolve("block"));
        try (Client writer = new Client(server.port())) {
            Thread.sleep(300);
            server.requestShutdown(false);
            Thread.sleep(300);
            assertEquals("OK", writer.call("SET", "k", "after-cancel"), "취소 뒤 보류한 쓰기 실행");
            assertEquals("after-cancel", writer.call("GET", "k"));
        }
        assertThrows(TimeoutException.class, () -> exitCode.get(200, TimeUnit.MILLISECONDS));
    }

    private static final class Client implements AutoCloseable {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;

        Client(int port) throws IOException {
            socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
            socket.setSoTimeout(10000);
            in = socket.getInputStream();
            out = socket.getOutputStream();
        }

        String call(String... argv) throws IOException {
            StringBuilder b = new StringBuilder("*" + argv.length + "\r\n");
            for (String arg : argv) {
                b.append('$').append(arg.length()).append("\r\n").append(arg).append("\r\n");
            }
            out.write(b.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
            return read();
        }

        private String read() throws IOException {
            String line = line();
            return switch (line.charAt(0)) {
                case '+', '-', ':' -> line.substring(1);
                case '$' -> line.equals("$-1") ? null : new String(in.readNBytes(Integer.parseInt(line.substring(1)) + 2),
                        StandardCharsets.UTF_8).stripTrailing();
                case '*' -> {
                    List<String> items = new ArrayList<>();
                    for (int i = Integer.parseInt(line.substring(1)); i > 0; i--) {
                        items.add(read());
                    }
                    yield items.toString();
                }
                default -> throw new IOException("모르는 응답: " + line);
            };
        }

        private String line() throws IOException {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            int b;
            while ((b = in.read()) != '\n') {
                if (b < 0) {
                    throw new IOException("끊김");
                }
                if (b != '\r') {
                    buf.write(b);
                }
            }
            return buf.toString(StandardCharsets.UTF_8);
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
