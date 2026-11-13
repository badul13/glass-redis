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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 레플리카 쪽 - glass 마스터에 glass 레플리카를 붙여 동기화·스트림·재접속·승격 확인 */
@Timeout(30)
class ReplicaTest {

    @TempDir
    Path dir;

    private final List<RedisServer> servers = new ArrayList<>();

    @AfterEach
    void stopAll() {
        servers.forEach(RedisServer::close);
    }

    /**
     * 디스크리스 지연 없음, PING 은 첫 cron 에만
     *
     * @param timeout repl-timeout - 짧으면 마스터 침묵으로 링크가 끊김
     */
    private RedisServer start(String name, long timeout, String masterHost, int masterPort) throws IOException {
        Path home = Files.createDirectories(dir.resolve(name));
        Replicator.Config d = Replicator.Config.defaults();
        Replicator.Config config = new Replicator.Config(true, 0, 0, d.backlogSize(), d.backlogTtl(), 3600,
                timeout, d.outputHardLimit(), d.outputSoftLimit(), d.outputSoftSeconds());
        RedisServer server = new RedisServer("127.0.0.1", 0, CommandRegistry.withBuiltins(), EventBus.NONE,
                new AppendOnlyLog(AppendOnlyLog.Config.defaults(home), EventBus.NONE),
                new Snapshot(new Snapshot.Config(home, "dump.rdb", List.of(), true)), config);
        if (masterHost != null) {
            server.replicaOf(masterHost, masterPort);
        }
        server.start();
        servers.add(server);
        return server;
    }

    @Test
    @DisplayName("전체 동기화 후 스트림 반영, 레플리카는 쓰기 거부, ROLE 은 connected 와 같은 오프셋")
    void fullSyncAndStream() throws Exception {
        RedisServer master = start("m", 60, null, 0);
        try (Client m = new Client(master.port())) {
            m.call("SET", "before", "1");
            m.call("RPUSH", "list", "a", "b");
            RedisServer replica = start("r", 60, "127.0.0.1", master.port());
            try (Client r = new Client(replica.port())) {
                await(() -> r.call("ROLE").contains("connected"));
                assertEquals("1", r.call("GET", "before"));

                m.call("SET", "after", "2");
                m.call("LPUSH", "list", "z");
                await(() -> "2".equals(r.call("GET", "after")));
                assertEquals("[z, a, b]", r.call("LRANGE", "list", "0", "-1"));
                assertEquals("READONLY You can't write against a read only replica.", r.call("SET", "x", "1"));

                String offset = offset(m.call("ROLE"));
                await(() -> r.call("ROLE").endsWith(", " + offset + "]"));
                assertTrue(r.call("ROLE").startsWith("[slave, 127.0.0.1, " + master.port() + ", connected"));
            }
        }
    }

    @Test
    @DisplayName("마스터가 조용하면 repl-timeout 뒤 끊고, 캐시된 마스터로 PSYNC - 끊긴 동안 쓴 것만 이어 받음")
    void reconnectsWithPartialResync() throws Exception {
        RedisServer master = start("m", 60, null, 0);
        RedisServer replica = start("r", 2, "127.0.0.1", master.port());
        try (Client m = new Client(master.port()); Client r = new Client(replica.port())) {
            await(() -> r.call("ROLE").contains("connected"));
            m.call("SET", "k", "1");
            await(() -> "1".equals(r.call("GET", "k")));

            // 마스터가 PING 을 안 보내니 2초 넘으면 레플리카가 링크를 끊고 다시 붙음
            String fullBefore = m.call("ROLE");
            Thread.sleep(3500);
            m.call("SET", "k", "2");
            await(() -> "2".equals(r.call("GET", "k")));
            assertTrue(r.call("ROLE").contains("connected"), fullBefore);
            assertEquals(1, master.replicator().syncFull(), "전체 동기화는 처음 한 번");
            assertTrue(master.replicator().syncPartialOk() >= 1, "재접속은 부분 동기화");
        }
    }

    @Test
    @DisplayName("체인 A→B→C, B 를 REPLICAOF NO ONE 으로 승격 - C 는 두 번째 ID 로 부분 동기화")
    void chainAndPromotion() throws Exception {
        RedisServer a = start("a", 60, null, 0);
        RedisServer b = start("b", 60, "127.0.0.1", a.port());
        try (Client ca = new Client(a.port()); Client cb = new Client(b.port())) {
            await(() -> cb.call("ROLE").contains("connected"));
            RedisServer c = start("c", 60, "127.0.0.1", b.port());
            try (Client cc = new Client(c.port())) {
                await(() -> cc.call("ROLE").contains("connected"));
                ca.call("SET", "chain", "1");
                await(() -> "1".equals(cc.call("GET", "chain")));
                String aOffset = offset(ca.call("ROLE"));
                await(() -> cb.call("ROLE").endsWith(", " + aOffset + "]"));
                await(() -> cc.call("ROLE").endsWith(", " + aOffset + "]"));

                assertEquals("OK", cb.call("REPLICAOF", "NO", "ONE"));
                cb.call("SET", "promoted", "yes");
                await(() -> "yes".equals(cc.call("GET", "promoted")));
                assertEquals(1, b.replicator().syncFull(), "C 의 첫 동기화만 전체");
                assertTrue(b.replicator().syncPartialOk() >= 1, "승격 뒤엔 부분 동기화");
            }
        }
    }

    /** [master, 오프셋, ...] 의 오프셋 */
    private static String offset(String role) {
        return role.split(", ")[1];
    }

    private static void await(IoCondition condition) throws Exception {
        for (int i = 0; i < 200; i++) {
            if (condition.test()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("조건이 10초 안에 맞지 않음");
    }

    @FunctionalInterface
    private interface IoCondition {
        boolean test() throws IOException;
    }

    /** 응답을 읽기 쉬운 문자열로 - 배열은 [a, b] */
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
