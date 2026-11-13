package glassredis.server;

import glassredis.command.CommandRegistry;
import glassredis.observe.EventBus;
import glassredis.persistence.AppendOnlyLog;
import glassredis.persistence.Snapshot;
import glassredis.replication.ReplicationMaster;
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
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 마스터 쪽 복제 - 레플리카 흉내를 내는 소켓으로 핸드셰이크·RDB·스트림 확인 */
@Timeout(20)
class ReplicationMasterTest {

    private static final String SELECT_0 = "*2\r\n$6\r\nSELECT\r\n$1\r\n0\r\n";

    @TempDir
    Path dir;

    private RedisServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
    }

    /** 디스크리스 지연 없음 - 바로 전송, PING 은 cron 첫 회에만 (오프셋 흔들림 방지) */
    private void start(boolean diskless) throws IOException {
        ReplicationMaster.Config defaults = ReplicationMaster.Config.defaults();
        ReplicationMaster.Config config = new ReplicationMaster.Config(diskless, 0, 0, defaults.backlogSize(),
                defaults.backlogTtl(), 3600, defaults.timeout(), defaults.outputHardLimit(),
                defaults.outputSoftLimit(), defaults.outputSoftSeconds());
        server = new RedisServer("127.0.0.1", 0, CommandRegistry.withBuiltins(), EventBus.NONE,
                new AppendOnlyLog(AppendOnlyLog.Config.defaults(dir), EventBus.NONE),
                new Snapshot(new Snapshot.Config(dir, "dump.rdb", List.of(), true)), config);
        server.start();
    }

    @Test
    @DisplayName("디스크리스 전체 동기화 - +FULLRESYNC, $EOF 형식 RDB, 첫 ACK 뒤 SELECT 와 명령 스트림")
    void disklessFullSync() throws IOException {
        start(true);
        try (Peer client = new Peer(server.port()); Peer replica = new Peer(server.port())) {
            assertEquals("+OK", client.command("SET", "before", "1"));

            assertEquals("+OK", replica.command("REPLCONF", "listening-port", "7777"));
            assertEquals("+OK", replica.command("REPLCONF", "capa", "eof", "capa", "psync2"));
            replica.send("PSYNC", "?", "-1");
            String[] fullresync = replica.lineSkippingNewlines().split(" ");
            assertEquals("+FULLRESYNC", fullresync[0]);
            assertEquals(40, fullresync[1].length());
            assertEquals("0", fullresync[2], "백로그를 처음 만들 때의 오프셋");

            String eof = replica.lineSkippingNewlines();
            assertTrue(eof.startsWith("$EOF:") && eof.length() == 45, eof);
            byte[] mark = eof.substring(5).getBytes(StandardCharsets.US_ASCII);
            byte[] rdb = replica.readUntil(mark);
            assertTrue(new String(rdb, 0, 9, StandardCharsets.US_ASCII).equals("REDIS0011"));
            assertTrue(contains(rdb, "repl-id"), "복제 정보 보조 필드");
            assertTrue(contains(rdb, "before"), "기존 키");

            // ACK 전에는 스트림을 안 보냄
            assertEquals("+OK", client.command("SET", "after", "2"));
            assertEquals("", replica.readFor(300));

            replica.send("REPLCONF", "ACK", "0");
            String expected = SELECT_0 + "*3\r\n$3\r\nSET\r\n$5\r\nafter\r\n$1\r\n2\r\n";
            assertEquals(expected, replica.readStream(expected.length()));

            // ROLE - 온라인 레플리카 [ip, 포트, ACK 오프셋], ACK 는 다른 연결이라 반영될 때까지
            // *3 / $6 / master / :<오프셋>
            String offset = client.rawCommand("ROLE").split("\r\n")[3].substring(1);
            replica.send("REPLCONF", "ACK", offset);
            String role = "";
            for (int i = 0; i < 20 && !role.endsWith("$" + offset.length() + "\r\n" + offset + "\r\n"); i++) {
                role = client.rawCommand("ROLE");
            }
            assertTrue(role.startsWith("*3\r\n$6\r\nmaster\r\n:" + offset + "\r\n*1\r\n*3\r\n$9\r\n127.0.0.1\r\n"
                    + "$4\r\n7777\r\n"), role);
        }
    }

    @Test
    @DisplayName("부분 동기화 - 끊긴 뒤 PSYNC <id> <다음 오프셋> 이면 +CONTINUE 와 놓친 스트림")
    void partialResync() throws IOException {
        start(true);
        String replid;
        long offset;
        try (Peer client = new Peer(server.port())) {
            try (Peer replica = new Peer(server.port())) {
                replica.command("REPLCONF", "capa", "eof", "capa", "psync2");
                replica.send("PSYNC", "?", "-1");
                String[] fullresync = replica.lineSkippingNewlines().split(" ");
                replid = fullresync[1];
                offset = Long.parseLong(fullresync[2]);
                String eof = replica.lineSkippingNewlines();
                replica.readUntil(eof.substring(5).getBytes(StandardCharsets.US_ASCII));
                replica.send("REPLCONF", "ACK", Long.toString(offset));
            }
            // 레플리카가 없는 동안의 쓰기 - 백로그에 남음
            client.command("SET", "missed", "x");

            try (Peer back = new Peer(server.port())) {
                back.command("REPLCONF", "capa", "eof", "capa", "psync2");
                back.send("PSYNC", replid, Long.toString(offset + 1));
                assertEquals("+CONTINUE " + replid, back.line());
                String expected = SELECT_0 + "*3\r\n$3\r\nSET\r\n$6\r\nmissed\r\n$1\r\nx\r\n";
                assertEquals(expected, back.readStream(expected.length()));
            }

            // 모르는 ID 면 전체 동기화
            try (Peer stranger = new Peer(server.port())) {
                stranger.send("PSYNC", "0".repeat(40), "1");
                assertTrue(stranger.lineSkippingNewlines().startsWith("+FULLRESYNC " + replid));
            }
        }
    }

    @Test
    @DisplayName("SYNC (EOF 능력 없음) - 디스크 BGSAVE 후 $<길이> 형식, +FULLRESYNC 없음")
    void syncUsesDisk() throws IOException {
        start(true);
        try (Peer client = new Peer(server.port()); Peer replica = new Peer(server.port())) {
            client.command("SET", "k", "v");
            replica.send("SYNC");
            String bulk = replica.lineSkippingNewlines();
            assertTrue(bulk.startsWith("$"), bulk);
            byte[] rdb = replica.readExactlyBytes(Integer.parseInt(bulk.substring(1)));
            assertTrue(contains(rdb, "REDIS0011") && contains(rdb, "k"));

            // 온라인 - ACK 없이도 스트림
            client.command("SET", "k", "w");
            String expected = SELECT_0 + "*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$1\r\nw\r\n";
            assertEquals(expected, replica.readStream(expected.length()));
        }
    }

    @Test
    @DisplayName("rdb-only - RDB 만 보내고 끊음, rdb-filter-only functions 면 키 없는 RDB")
    void rdbOnlyAndFilter() throws IOException {
        start(true);
        try (Peer client = new Peer(server.port())) {
            client.command("SET", "secret", "v");
        }
        for (boolean filtered : new boolean[]{false, true}) {
            try (Peer replica = new Peer(server.port())) {
                assertEquals("+OK", replica.command("REPLCONF", "rdb-only", "1"));
                if (filtered) {
                    assertEquals("+OK", replica.command("REPLCONF", "rdb-filter-only", "functions"));
                }
                replica.command("REPLCONF", "capa", "eof");
                replica.send("SYNC");
                String eof = replica.lineSkippingNewlines();
                assertTrue(eof.startsWith("$EOF:"), "rdb-only 는 디스크리스: " + eof);
                byte[] rdb = replica.readUntil(eof.substring(5).getBytes(StandardCharsets.US_ASCII));
                assertEquals(!filtered, contains(rdb, "secret"));
                assertTrue(replica.closedByServer(), "RDB 뒤 연결 종료");
            }
        }
    }

    @Test
    @DisplayName("레플리카가 응답 있는 명령을 보내면 끊음")
    void replicaThatGetsReplyIsDropped() throws IOException {
        start(false);
        try (Peer replica = new Peer(server.port())) {
            replica.command("REPLCONF", "capa", "psync2");
            replica.send("PSYNC", "?", "-1");
            assertTrue(replica.lineSkippingNewlines().startsWith("+FULLRESYNC"));
            String bulk = replica.lineSkippingNewlines();
            replica.readExactlyBytes(Integer.parseInt(bulk.substring(1)));

            replica.send("PING");
            assertTrue(replica.closedByServer(), "PING 응답 대신 연결 끊김");
        }
    }

    private static boolean contains(byte[] haystack, String needle) {
        return new String(haystack, StandardCharsets.ISO_8859_1).contains(needle);
    }

    /** RESP 를 직접 주고받는 소켓 */
    private static final class Peer implements AutoCloseable {

        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;

        Peer(int port) throws IOException {
            socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
            socket.setSoTimeout(10000);
            in = socket.getInputStream();
            out = socket.getOutputStream();
        }

        void send(String... argv) throws IOException {
            StringBuilder b = new StringBuilder("*" + argv.length + "\r\n");
            for (String arg : argv) {
                b.append('$').append(arg.length()).append("\r\n").append(arg).append("\r\n");
            }
            out.write(b.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
        }

        String command(String... argv) throws IOException {
            send(argv);
            return line();
        }

        /** 응답 전체를 대략 - 배열도 한 덩어리 문자열로 */
        String rawCommand(String... argv) throws IOException {
            send(argv);
            return readFor(300);
        }

        String line() throws IOException {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            int b;
            while ((b = in.read()) != '\n') {
                if (b == -1) {
                    throw new IOException("끊김");
                }
                buffer.write(b);
            }
            String text = buffer.toString(StandardCharsets.ISO_8859_1);
            return text.endsWith("\r") ? text.substring(0, text.length() - 1) : text;
        }

        /** RDB 대기 중 오는 줄바꿈 건너뛰기 */
        String lineSkippingNewlines() throws IOException {
            String text;
            do {
                text = line();
            } while (text.isEmpty());
            return text;
        }

        byte[] readUntil(byte[] mark) throws IOException {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            while (true) {
                int b = in.read();
                if (b == -1) {
                    throw new IOException("끊김");
                }
                buffer.write(b);
                byte[] data = buffer.toByteArray();
                if (data.length >= mark.length && java.util.Arrays.equals(data, data.length - mark.length,
                        data.length, mark, 0, mark.length)) {
                    return java.util.Arrays.copyOf(data, data.length - mark.length);
                }
            }
        }

        byte[] readExactlyBytes(int count) throws IOException {
            return in.readNBytes(count);
        }

        /** 복제 스트림 count 바이트 - 주기 PING(cron 첫 회 등)은 건너뜀 */
        String readStream(int count) throws IOException {
            String ping = "*1\r\n$4\r\nping\r\n";
            String head;
            do {
                head = new String(in.readNBytes(ping.length()), StandardCharsets.ISO_8859_1);
            } while (head.equals(ping));
            return head + new String(in.readNBytes(count - ping.length()), StandardCharsets.ISO_8859_1);
        }

        String readFor(int millis) throws IOException {
            socket.setSoTimeout(millis);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try {
                int b;
                while ((b = in.read()) != -1) {
                    buffer.write(b);
                }
            } catch (SocketTimeoutException e) {
                // 끝
            } finally {
                socket.setSoTimeout(10000);
            }
            return buffer.toString(StandardCharsets.ISO_8859_1);
        }

        boolean closedByServer() throws IOException {
            try {
                return in.read() == -1;
            } catch (IOException e) {
                return true;
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
