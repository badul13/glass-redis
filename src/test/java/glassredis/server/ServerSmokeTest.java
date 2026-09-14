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

/**
 * 진짜 소켓으로 서버와 대화해 본다.
 *
 * <p>이 PC 에는 redis-cli 가 없고 CI 에도 있으리라는 보장이 없다. 그래서 클라이언트 역할을
 * 테스트 코드가 직접 한다 — 바이트를 손으로 만들어 보내고 돌아온 바이트를 그대로 비교한다.
 * 덕분에 "실제로 프로토콜을 지키는가"를 외부 도구 없이 검증할 수 있다.
 *
 * <p>포트는 0 으로 연다. OS 가 비어 있는 포트를 골라주므로 다른 프로그램과 부딪히지 않는다.
 */
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
    @DisplayName("PING 에 +PONG 으로 답한다")
    void ping() throws IOException {
        try (Client client = connect()) {
            assertEquals("+PONG", client.command("*1\r\n$4\r\nPING\r\n"));
        }
    }

    @Test
    @DisplayName("PING 에 인자를 주면 그 값을 벌크 문자열로 돌려준다")
    void pingWithMessage() throws IOException {
        try (Client client = connect()) {
            client.send("*2\r\n$4\r\nPING\r\n$5\r\nhello\r\n");
            assertEquals("$5", client.readLine());
            assertEquals("hello", client.readLine());
        }
    }

    @Test
    @DisplayName("ECHO 는 받은 값을 그대로 돌려준다")
    void echo() throws IOException {
        try (Client client = connect()) {
            client.send("*2\r\n$4\r\nECHO\r\n$5\r\nworld\r\n");
            assertEquals("$5", client.readLine());
            assertEquals("world", client.readLine());
        }
    }

    @Test
    @DisplayName("명령 이름의 대소문자를 가리지 않는다")
    void commandNamesAreCaseInsensitive() throws IOException {
        try (Client client = connect()) {
            assertEquals("+PONG", client.command("*1\r\n$4\r\nping\r\n"));
            assertEquals("+PONG", client.command("*1\r\n$4\r\nPiNg\r\n"));
        }
    }

    @Test
    @DisplayName("인라인 명령도 받는다 (telnet 으로 칠 수 있다)")
    void inlineCommand() throws IOException {
        try (Client client = connect()) {
            assertEquals("+PONG", client.command("PING\r\n"));
        }
    }

    @Test
    @DisplayName("모르는 명령에는 에러를 주지만 커넥션은 유지한다")
    void unknownCommandKeepsConnection() throws IOException {
        try (Client client = connect()) {
            assertEquals("-ERR unknown command 'NOPE', with args beginning with: ", client.command("NOPE\r\n"));
            // 같은 커넥션에서 다음 명령이 계속 동작해야 한다
            assertEquals("+PONG", client.command("PING\r\n"));
        }
    }

    @Test
    @DisplayName("인자 개수가 틀리면 에러를 주지만 커넥션은 유지한다")
    void wrongArityKeepsConnection() throws IOException {
        try (Client client = connect()) {
            assertEquals("-ERR wrong number of arguments for 'echo' command", client.command("ECHO a b\r\n"));
            assertEquals("+PONG", client.command("PING\r\n"));
        }
    }

    @Test
    @DisplayName("프로토콜이 깨지면 에러를 보내고 커넥션을 닫는다")
    void protocolErrorClosesConnection() throws IOException {
        try (Client client = connect()) {
            String reply = client.command("*abc\r\n");
            assertTrue(reply.startsWith("-ERR Protocol error:"), "실제 응답: " + reply);
            assertTrue(client.isClosedByServer(), "프로토콜 에러 뒤에는 서버가 커넥션을 닫아야 한다");
        }
    }

    @Test
    @DisplayName("파이프라이닝: 명령을 몰아서 보내면 응답도 순서대로 돌아온다")
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
    @DisplayName("바이너리 값이 왕복해도 한 바이트도 변하지 않는다")
    void binaryRoundTrip() throws IOException {
        try (Client client = connect()) {
            // 값 안에 CRLF 와 0x00 을 넣는다. 문자열로 다뤘다면 여기서 망가진다.
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
    @DisplayName("QUIT 은 +OK 를 보낸 뒤 커넥션을 닫는다")
    void quit() throws IOException {
        try (Client client = connect()) {
            assertEquals("+OK", client.command("QUIT\r\n"));
            assertTrue(client.isClosedByServer());
        }
    }

    @Test
    @DisplayName("한 커넥션에서 SET 한 값을 다른 커넥션에서 GET 으로 읽는다")
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
    @DisplayName("SET 의 EX 옵션으로 건 만료 시간을 TTL 로 읽는다")
    void setWithExpiryAndTtl() throws IOException {
        try (Client client = connect()) {
            assertEquals("+OK", client.command("SET smoke:ttl v EX 100\r\n"));
            assertEquals(":100", client.command("TTL smoke:ttl\r\n"));
        }
    }

    @Test
    @DisplayName("여러 클라이언트가 같은 키에 동시에 INCR 해도 증가분이 하나도 사라지지 않는다")
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
    @DisplayName("명령 실행 중 Error 가 나면 클라이언트를 멈춘 채 두지 않고 서버가 종료된다")
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

        // 공유 서버를 죽이면 다른 테스트가 깨지므로 이 테스트만의 서버를 띄운다.
        try (RedisServer doomed = new RedisServer("127.0.0.1", 0, registry)) {
            doomed.start();
            try (Client client = new Client(doomed.port())) {
                client.send("CRASH\r\n");
                assertTrue(client.isClosedByServer(), "응답을 기다리며 멈추지 않고 커넥션이 끊겨야 한다");
            }
            doomed.awaitStop(); // 서버가 멈추지 않았다면 @Timeout 에 걸린다
        }
    }

    private static Client connect() throws IOException {
        return new Client(server.port());
    }

    /** 테스트용 최소 클라이언트. 바이트를 직접 주고받는다. */
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

        /** CRLF 까지 한 줄. 종결자는 빼고 준다. */
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

        /** 서버가 자기 쪽에서 커넥션을 닫았는지. 닫혔다면 읽기가 EOF(-1) 를 준다. */
        boolean isClosedByServer() throws IOException {
            return in.read() == -1;
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
