package glassredis.server;

import glassredis.command.Command;
import glassredis.command.CommandRegistry;
import glassredis.command.Context;
import glassredis.resp.RespValue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** redis-cli 없이 실제 소켓으로 바이트를 주고받아 확인 */
@Timeout(10)
class ServerSmokeTest {

    private static RedisServer server;

    @BeforeAll
    static void startServer() throws IOException {
        server = new RedisServer("127.0.0.1", 0, CommandRegistry.withBuiltins());
        server.start();
    }

    @AfterAll
    static void stopServer() {
        server.close();
    }

    @Test
    @DisplayName("PING 에 +PONG 응답")
    void ping() throws IOException {
        try (Client client = connect()) {
            assertEquals("+PONG", client.command("*1\r\n$4\r\nPING\r\n"));
        }
    }

    @Test
    @DisplayName("인자 있는 PING - 그 값을 벌크 문자열로 반환")
    void pingWithMessage() throws IOException {
        try (Client client = connect()) {
            client.send("*2\r\n$4\r\nPING\r\n$5\r\nhello\r\n");
            assertEquals("$5", client.readLine());
            assertEquals("hello", client.readLine());
        }
    }

    @Test
    @DisplayName("ECHO - 받은 값 그대로 반환")
    void echo() throws IOException {
        try (Client client = connect()) {
            client.send("*2\r\n$4\r\nECHO\r\n$5\r\nworld\r\n");
            assertEquals("$5", client.readLine());
            assertEquals("world", client.readLine());
        }
    }

    @Test
    @DisplayName("명령 이름 대소문자 무시")
    void commandNamesAreCaseInsensitive() throws IOException {
        try (Client client = connect()) {
            assertEquals("+PONG", client.command("*1\r\n$4\r\nping\r\n"));
            assertEquals("+PONG", client.command("*1\r\n$4\r\nPiNg\r\n"));
        }
    }

    @Test
    @DisplayName("인라인 명령도 수용 (telnet 으로 입력 가능)")
    void inlineCommand() throws IOException {
        try (Client client = connect()) {
            assertEquals("+PONG", client.command("PING\r\n"));
        }
    }

    @Test
    @DisplayName("모르는 명령은 에러, 커넥션은 유지")
    void unknownCommandKeepsConnection() throws IOException {
        try (Client client = connect()) {
            assertEquals("-ERR unknown command 'NOPE', with args beginning with: ", client.command("NOPE\r\n"));
            assertEquals("+PONG", client.command("PING\r\n"));
        }
    }

    @Test
    @DisplayName("인자 개수 오류는 에러, 커넥션은 유지")
    void wrongArityKeepsConnection() throws IOException {
        try (Client client = connect()) {
            assertEquals("-ERR wrong number of arguments for 'echo' command", client.command("ECHO a b\r\n"));
            assertEquals("+PONG", client.command("PING\r\n"));
        }
    }

    @Test
    @DisplayName("프로토콜 오류 시 에러 전송 후 커넥션 종료")
    void protocolErrorClosesConnection() throws IOException {
        try (Client client = connect()) {
            String reply = client.command("*abc\r\n");
            assertTrue(reply.startsWith("-ERR Protocol error:"), "실제 응답: " + reply);
            assertTrue(client.isClosedByServer(), "프로토콜 에러 뒤에는 서버가 커넥션을 닫아야 한다");
        }
    }

    @Test
    @DisplayName("파이프라이닝 - 명령을 몰아서 보내도 응답은 순서대로")
    void pipelining() throws IOException {
        try (Client client = connect()) {
            client.send("*1\r\n$4\r\nPING\r\n*2\r\n$4\r\nECHO\r\n$2\r\nhi\r\n*1\r\n$4\r\nPING\r\n");
            assertEquals("+PONG", client.readLine());
            assertEquals("$2", client.readLine());
            assertEquals("hi", client.readLine());
            assertEquals("+PONG", client.readLine());
        }
    }

    @Test
    @DisplayName("바이너리 값 왕복 시 바이트 변화 없음")
    void binaryRoundTrip() throws IOException {
        try (Client client = connect()) {
            // 값에 CRLF 와 0x00 포함
            byte[] payload = {'a', '\r', '\n', 0x00, 'b'};
            ByteArrayOutputStream request = new ByteArrayOutputStream();
            request.write("*2\r\n$4\r\nECHO\r\n$5\r\n".getBytes(StandardCharsets.US_ASCII));
            request.write(payload);
            request.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            client.sendRaw(request.toByteArray());

            assertEquals("$5", client.readLine());
            byte[] echoed = client.readExactly(payload.length);
            assertEquals(0x00, echoed[3]);
            assertEquals('\r', echoed[1]);
            assertEquals('\n', echoed[2]);
        }
    }

    @Test
    @DisplayName("QUIT - +OK 전송 후 커넥션 종료")
    void quit() throws IOException {
        try (Client client = connect()) {
            assertEquals("+OK", client.command("QUIT\r\n"));
            assertTrue(client.isClosedByServer());
        }
    }

    @Test
    @DisplayName("한 커넥션에서 SET 후 다른 커넥션에서 GET 조회")
    void setAndGetAcrossConnections() throws IOException {
        try (Client writer = connect(); Client reader = connect()) {
            assertEquals("+OK", writer.command("SET smoke:greeting hello\r\n"));
            reader.send("GET smoke:greeting\r\n");
            assertEquals("$5", reader.readLine());
            assertEquals("hello", reader.readLine());
            assertEquals("$-1", reader.command("GET smoke:missing\r\n"));
        }
    }

    @Test
    @DisplayName("SET EX 로 건 만료 시간을 TTL 로 조회")
    void setWithExpiryAndTtl() throws IOException {
        try (Client client = connect()) {
            assertEquals("+OK", client.command("SET smoke:ttl v EX 100\r\n"));
            assertEquals(":100", client.command("TTL smoke:ttl\r\n"));
        }
    }

    @Test
    @DisplayName("여러 클라이언트가 같은 키에 동시 INCR 해도 증가분 유실 없음")
    void concurrentIncrLosesNothing() throws Exception {
        int clients = 50;
        int incrementsPerClient = 1000;

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Void>> finished = new ArrayList<>();
            for (int c = 0; c < clients; c++) {
                finished.add(pool.submit(() -> {
                    try (Client client = connect()) {
                        for (int i = 0; i < incrementsPerClient; i++) {
                            String reply = client.command("INCR smoke:counter\r\n");
                            if (!reply.startsWith(":")) {
                                throw new AssertionError("INCR 응답이 정수가 아닙니다: " + reply);
                            }
                        }
                    }
                    return null;
                }));
            }
            for (Future<Void> done : finished) {
                done.get();
            }
        }

        String expected = String.valueOf(clients * incrementsPerClient);
        try (Client client = connect()) {
            client.send("GET smoke:counter\r\n");
            assertEquals("$" + expected.length(), client.readLine());
            assertEquals(expected, client.readLine());
        }
    }

    @Test
    @DisplayName("명령 실행 중 Error 발생 시 클라이언트를 멈춘 채 두지 않고 서버 종료")
    void fatalErrorStopsServer() throws Exception {
        CommandRegistry registry = CommandRegistry.withBuiltins();
        registry.register(new Command() {
            @Override
            public String name() {
                return "CRASH";
            }

            @Override
            public RespValue execute(Context ctx, List<byte[]> args) {
                throw new StackOverflowError("테스트에서 일부러 던짐");
            }
        });

        // 공유 서버를 죽이면 다른 테스트가 깨지므로 별도 기동
        try (RedisServer doomed = new RedisServer("127.0.0.1", 0, registry)) {
            doomed.start();
            try (Client client = new Client(doomed.port())) {
                client.send("CRASH\r\n");
                assertTrue(client.isClosedByServer(), "응답을 기다리며 멈추지 않고 커넥션이 끊겨야 한다");
            }
            doomed.awaitStop(); // 서버가 멈추지 않으면 @Timeout 으로 실패
        }
    }

    private static Client connect() throws IOException {
        return new Client(server.port());
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

        String command(String raw) throws IOException {
            send(raw);
            return readLine();
        }

        void send(String raw) throws IOException {
            sendRaw(raw.getBytes(StandardCharsets.UTF_8));
        }

        void sendRaw(byte[] raw) throws IOException {
            out.write(raw);
            out.flush();
        }

        /** CRLF 를 뺀 한 줄 */
        String readLine() throws IOException {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            int b;
            while ((b = in.read()) != -1) {
                if (b == '\n') {
                    break;
                }
                if (b != '\r') {
                    buffer.write(b);
                }
            }
            return buffer.toString(StandardCharsets.UTF_8);
        }

        byte[] readExactly(int count) throws IOException {
            byte[] data = new byte[count];
            int offset = 0;
            while (offset < count) {
                int read = in.read(data, offset, count - offset);
                if (read == -1) {
                    throw new IOException("기대한 " + count + " 바이트를 다 받기 전에 스트림이 끊겼습니다");
                }
                offset += read;
            }
            return data;
        }

        boolean isClosedByServer() throws IOException {
            return in.read() == -1;
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
