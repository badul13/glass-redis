package glassredis.server;

import glassredis.command.CommandRegistry;
import glassredis.observe.EventBus;
import glassredis.persistence.AppendOnlyLog;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 실제 소켓으로 쓰고, 서버를 껐다 켜서 AOF 복구 확인 */
@Timeout(20)
class AofRestartTest {

    @TempDir
    Path dir;

    private RedisServer start() throws IOException {
        return start(false);
    }

    private RedisServer start(boolean rdbPreamble) throws IOException {
        AppendOnlyLog aof = new AppendOnlyLog(new AppendOnlyLog.Config(dir, true, AppendOnlyLog.FsyncPolicy.EVERYSEC,
                100, 64L << 20, rdbPreamble), EventBus.NONE);
        RedisServer server = new RedisServer("127.0.0.1", 0, CommandRegistry.withBuiltins(), EventBus.NONE, aof);
        server.start();
        return server;
    }

    @Test
    @DisplayName("껐다 켜도 데이터 유지 - 응답 받은 쓰기는 이미 파일에 있음")
    void survivesRestart() throws Exception {
        RedisServer first = start();
        try (Client client = new Client(first.port())) {
            assertEquals("+OK", client.command("SET", "greeting", "hello"));
            assertEquals(":3", client.command("RPUSH", "list", "a", "b", "c"));
            assertEquals(":1", client.command("EXPIRE", "greeting", "1000"));
            assertEquals(":1", client.command("INCR", "counter"));
            // 응답 전에 write 끝 - 서버를 끄기 전에도 파일에 있어야 함
            String incr = Files.readString(dir.resolve("appendonlydir").resolve("appendonly.aof.1.incr.aof"));
            assertTrue(incr.contains("INCR"), incr);
        }
        first.close();

        RedisServer second = start();
        try (Client client = new Client(second.port())) {
            assertEquals("$5", client.command("GET", "greeting"));
            assertEquals("hello", client.readLine());
            assertEquals(":3", client.command("LLEN", "list"));
            assertEquals("$1", client.command("GET", "counter"));
            assertEquals("1", client.readLine());
            String ttl = client.command("TTL", "greeting");
            assertTrue(ttl.equals(":1000") || ttl.equals(":999"), "만료 시각은 절대값이라 재시작해도 그대로: " + ttl);
        } finally {
            second.close();
        }
    }

    @Test
    @DisplayName("BGREWRITEAOF 후 재시작 - 새 base 로 복구")
    void rewriteThenRestart() throws Exception {
        RedisServer first = start();
        try (Client client = new Client(first.port())) {
            for (int i = 0; i < 30; i++) {
                client.command("INCR", "n");
            }
            assertEquals("+Background append only file rewriting started", client.command("BGREWRITEAOF"));
            Path manifest = dir.resolve("appendonlydir").resolve("appendonly.aof.manifest");
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (!Files.readString(manifest).contains("2.base")) {
                assertTrue(System.nanoTime() < deadline, "5초 안에 재작성 완료");
                Thread.sleep(20);
            }
        } finally {
            first.close();
        }

        RedisServer second = start();
        try (Client client = new Client(second.port())) {
            assertEquals("$2", client.command("GET", "n"));
            assertEquals("30", client.readLine());
        } finally {
            second.close();
        }
    }

    @Test
    @DisplayName("preamble yes - BGREWRITEAOF 후 RDB base + 그 뒤 incr 명령으로 복구")
    void rdbPreambleRestart() throws Exception {
        RedisServer first = start(true);
        Path aofDir = dir.resolve("appendonlydir");
        try (Client client = new Client(first.port())) {
            assertEquals("+OK", client.command("SET", "greeting", "hello"));
            assertEquals(":1", client.command("EXPIRE", "greeting", "1000"));
            assertEquals(":3", client.command("RPUSH", "list", "a", "b", "c"));
            assertEquals(":2", client.command("SADD", "set", "1", "2"));
            assertEquals(":2", client.command("HSET", "hash", "f1", "v1", "f2", "v2"));
            assertEquals(":2", client.command("ZADD", "zset", "1", "a", "2.5", "b"));
            assertEquals("+Background append only file rewriting started", client.command("BGREWRITEAOF"));
            Path manifest = aofDir.resolve("appendonly.aof.manifest");
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (!Files.readString(manifest).contains("appendonly.aof.2.base.rdb")) {
                assertTrue(System.nanoTime() < deadline, "5초 안에 재작성 완료");
                Thread.sleep(20);
            }
            assertEquals("+OK", client.command("SET", "after", "rewrite"));
            assertEquals(":4", client.command("RPUSH", "list", "d"));
        } finally {
            first.close();
        }
        byte[] base = Files.readAllBytes(aofDir.resolve("appendonly.aof.2.base.rdb"));
        assertEquals("REDIS0011", new String(base, 0, 9, StandardCharsets.US_ASCII));

        RedisServer second = start(true);
        try (Client client = new Client(second.port())) {
            assertEquals("$5", client.command("GET", "greeting"));
            assertEquals("hello", client.readLine());
            String ttl = client.command("TTL", "greeting");
            assertTrue(ttl.equals(":1000") || ttl.equals(":999"), "base 의 만료 시각 유지: " + ttl);
            assertEquals(":4", client.command("LLEN", "list"));
            assertEquals(":1", client.command("SISMEMBER", "set", "2"));
            assertEquals("$2", client.command("HGET", "hash", "f2"));
            assertEquals("v2", client.readLine());
            assertEquals("$3", client.command("ZSCORE", "zset", "b"));
            assertEquals("2.5", client.readLine());
            assertEquals("$7", client.command("GET", "after"));
            assertEquals("rewrite", client.readLine());
        } finally {
            second.close();
        }
    }

    @Test
    @DisplayName("RDB base 의 지난 만료 키 - 기동 시 실은 뒤 주기적 만료가 지우고 incr 에 DEL")
    void expiredKeyInRdbBase() throws Exception {
        RedisServer first = start(true);
        Path aofDir = dir.resolve("appendonlydir");
        try (Client client = new Client(first.port())) {
            assertEquals("+OK", client.command("SET", "k", "v", "PX", "1500"));
            assertEquals("+OK", client.command("SET", "p", "v"));
            assertEquals("+Background append only file rewriting started", client.command("BGREWRITEAOF"));
            Path manifest = aofDir.resolve("appendonly.aof.manifest");
            long deadline = System.nanoTime() + 1_000_000_000L;
            while (!Files.readString(manifest).contains("appendonly.aof.2.base.rdb")) {
                assertTrue(System.nanoTime() < deadline, "만료 전에 재작성 완료");
                Thread.sleep(10);
            }
        } finally {
            first.close();
        }
        Path incr = aofDir.resolve("appendonly.aof.2.incr.aof");
        assertEquals("", Files.readString(incr), "만료 전에 꺼서 DEL 없음");
        Thread.sleep(1600);

        RedisServer second = start(true);
        try {
            assertEquals(2, second.aofSnapshot().loadedKeys(), "지난 만료 키 k 도 실음");
            long deadline = System.nanoTime() + 3_000_000_000L;
            while (!Files.readString(incr).contains("DEL")) {
                assertTrue(System.nanoTime() < deadline, "3초 안에 주기적 만료");
                Thread.sleep(20);
            }
            assertEquals("*2\r\n$6\r\nSELECT\r\n$1\r\n0\r\n*2\r\n$3\r\nDEL\r\n$1\r\nk\r\n", Files.readString(incr));
            try (Client client = new Client(second.port())) {
                assertEquals(":0", client.command("EXISTS", "k"));
                assertEquals(":1", client.command("EXISTS", "p"));
            }
        } finally {
            second.close();
        }
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
