package glassredis.server;

import glassredis.command.Command;
import glassredis.command.CommandRegistry;
import glassredis.command.Errors;
import glassredis.resp.RespProtocolException;
import glassredis.resp.RespReader;
import glassredis.resp.RespValue;
import glassredis.resp.RespWriter;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * 커넥션 하나의 생명주기. 가상 스레드 하나가 이 객체 하나를 끝까지 담당한다.
 *
 * <p>커넥션당 스레드 하나라는 구조는 원래 확장성이 나쁜 것으로 악명 높았다.
 * OS 스레드는 하나에 기본 1MB 스택을 잡고 컨텍스트 스위칭 비용도 커서,
 * 만 개를 만들면 서버가 버티지 못한다. 그래서 전통적으로는 NIO 셀렉터와 콜백으로
 * 짜야 했고 코드가 뒤집혔다.
 *
 * <p>가상 스레드는 블로킹 읽기에서 멈출 때 바탕이 되는 OS 스레드를 반납한다.
 * 그래서 아래처럼 "읽고, 처리하고, 쓰고, 반복"이라는 가장 읽기 쉬운 형태를 유지하면서도
 * 커넥션 수천 개를 감당할 수 있다. 이 프로젝트가 Java 21 을 요구하는 이유다.
 */
final class Connection implements Runnable {

    private final Socket socket;
    private final CommandRegistry registry;
    private final long id;

    Connection(Socket socket, CommandRegistry registry, long id) {
        this.socket = socket;
        this.registry = registry;
        this.id = id;
    }

    @Override
    public void run() {
        String peer = String.valueOf(socket.getRemoteSocketAddress());
        try (Socket open = socket) {
            // Nagle 알고리즘은 작은 패킷을 모았다가 보낸다. 처리량에는 도움이 되지만
            // 요청-응답을 주고받는 구조에서는 응답이 최대 수십 ms 지연될 수 있어서 끈다.
            open.setTcpNoDelay(true);

            RespReader reader = new RespReader(open.getInputStream());
            RespWriter writer = new RespWriter(open.getOutputStream());
            serve(reader, writer);
        } catch (IOException e) {
            // 클라이언트가 갑자기 끊는 건 흔한 일이라 경고로 남기지 않는다.
            log("커넥션 %d (%s) 입출력 종료: %s", id, peer, e.getMessage());
        }
    }

    private void serve(RespReader reader, RespWriter writer) throws IOException {
        while (true) {
            List<byte[]> argv;
            try {
                argv = reader.readCommand();
            } catch (RespProtocolException e) {
                // 프레이밍이 깨졌다. 어디서부터가 다음 명령인지 알 수 없으므로
                // 에러만 알리고 커넥션을 닫는다. 실제 Redis 도 이렇게 한다.
                writer.write(Errors.protocol(e.getMessage()));
                writer.flush();
                return;
            }

            if (argv == null) {
                return; // 클라이언트가 정상적으로 끊었다
            }
            if (argv.isEmpty()) {
                continue; // 빈 줄이나 빈 배열은 무시하고 다음 명령을 기다린다
            }

            // 명령 이름만 문자열로 바꾼다. 인자는 바이너리일 수 있으므로 건드리지 않는다.
            String name = new String(argv.get(0), StandardCharsets.US_ASCII).toUpperCase(Locale.ROOT);
            Command command = registry.find(name);

            RespValue reply;
            if (command == null) {
                // 모르는 명령은 프레이밍이 멀쩡하므로 커넥션을 유지한 채 에러만 돌려준다.
                reply = Errors.unknownCommand(name);
            } else {
                try {
                    reply = command.execute(argv.subList(1, argv.size()));
                } catch (RuntimeException e) {
                    // 명령 구현의 버그로 커넥션 전체가 죽지 않도록 막아둔다.
                    log("명령 %s 처리 중 예외: %s", name, e);
                    reply = Errors.internal(e.getClass().getSimpleName());
                }
            }

            writer.write(reply);
            writer.flush();

            if (command != null && command.closesConnection()) {
                return;
            }
        }
    }

    private static void log(String format, Object... args) {
        System.out.println("[glass-redis] " + String.format(format, args));
    }
}
