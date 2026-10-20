package glassredis.server;

import glassredis.command.CommandRegistry;
import glassredis.observe.EventBus;
import glassredis.persistence.AppendOnlyLog;
import glassredis.persistence.Snapshot;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 실제 소켓으로 SAVE 하고, 서버를 껐다 켜서 dump.rdb 복구 확인 */
@Timeout(20)
class RdbRestartTest {

    @TempDir
    Path dir;

    private RedisServer start(boolean appendOnly) throws IOException {
        AppendOnlyLog aof = new AppendOnlyLog(new AppendOnlyLog.Config(dir, appendOnly,
                AppendOnlyLog.FsyncPolicy.EVERYSEC, 100, 64L << 20), EventBus.NONE);
        RedisServer server = new RedisServer("127.0.0.1", 0, CommandRegistry.withBuiltins(), EventBus.NONE, aof,
                new Snapshot(Snapshot.Config.defaults(dir)));
        server.start();
        return server;
    }

    @Test
    @DisplayName("SAVE 후 재시작 - 자료형·인코딩·만료 그대로, SAVE 뒤 쓰기는 사라짐")
    void saveThenRestart() throws Exception {
        RedisServer first = start(false);
        try (Client client = new Client(first.port())) {
            assertEquals("+OK", client.command("SET", "greeting", "hello", "EX", "1000"));
            assertEquals(":3", client.command("RPUSH", "list", "a", "b", "c"));
            assertEquals(":2", client.command("SADD", "set", "1", "2"));
            assertEquals(":1", client.command("ZADD", "zset", "1.5", "m"));
            assertEquals(":1", client.command("HSET", "hash", "f", "v"));
            assertEquals("+OK", client.command("SAVE"));
            assertTrue(Files.exists(dir.resolve("dump.rdb")));
            assertEquals("+OK", client.command("SET", "late", "not saved"));
        } finally {
            first.close();
        }

        RedisServer second = start(false);
        try (Client client = new Client(second.port())) {
            assertEquals("$5", client.command("GET", "greeting"));
            assertEquals("hello", client.readLine());
            String ttl = client.command("TTL", "greeting");
            assertTrue(ttl.equals(":1000") || ttl.equals(":999"), "만료 시각은 절대값이라 재시작해도 그대로: " + ttl);
            assertEquals(":3", client.command("LLEN", "list"));
            assertEquals("$6", client.command("OBJECT", "ENCODING", "set"));
            assertEquals("intset", client.readLine());
            assertEquals("$3", client.command("ZSCORE", "zset", "m"));
            assertEquals("1.5", client.readLine());
            assertEquals("$1", client.command("HGET", "hash", "f"));
            assertEquals("v", client.readLine());
            assertEquals("$-1", client.command("GET", "late"));
        } finally {
            second.close();
        }
        // 임시 파일은 이름 바꾸기로 사라짐
        try (var files = Files.list(dir)) {
            assertFalse(files.anyMatch(p -> p.getFileName().toString().startsWith("temp-")));
        }
    }

    @Test
    @DisplayName("AOF 켜져 있으면 dump.rdb 는 안 읽음 - loadDataFromDisk")
    void aofWins() throws Exception {
        RedisServer first = start(false);
        try (Client client = new Client(first.port())) {
            client.command("SET", "from", "rdb");
            assertEquals("+OK", client.command("SAVE"));
        } finally {
            first.close();
        }

        RedisServer second = start(true);
        try (Client client = new Client(second.port())) {
            assertEquals("$-1", client.command("GET", "from"));
        } finally {
            second.close();
        }
    }

    @Test
    @DisplayName("깨진 dump.rdb 면 기동 중단")
    void corruptFileStopsStartup() throws Exception {
        Files.write(dir.resolve("dump.rdb"), "REDIS0011þ".getBytes(StandardCharsets.ISO_8859_1));
        IOException e = assertThrows(IOException.class, () -> start(false));
        assertTrue(e.getMessage().contains("Unexpected EOF"), e.getMessage());
    }

    private static final class Client implements AutoCloseable {

        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;

        Client(int port) throws IOException {
            socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
            socket.setSoTimeout(5000);
            in = socket.getInputStream();
            out = socket.getOutputStream();
        }

        /** 응답 첫 줄 */
        String command(String... args) throws IOException {
            StringBuilder raw = new StringBuilder("*" + args.length + "\r\n");
            for (String arg : args) {
                raw.append('$').append(arg.getBytes(StandardCharsets.UTF_8).length).append("\r\n").append(arg).append("\r\n");
            }
            out.write(raw.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
            return readLine();
        }

        String readLine() throws IOException {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            int b;
            while ((b = in.read()) != -1 && b != '\n') {
                if (b != '\r') {
                    buffer.write(b);
                }
            }
            return buffer.toString(StandardCharsets.UTF_8);
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
