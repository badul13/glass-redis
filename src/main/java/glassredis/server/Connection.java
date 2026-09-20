package glassredis.server;

import glassredis.command.Command;
import glassredis.command.CommandRegistry;
import glassredis.command.Errors;
import glassredis.observe.Event;
import glassredis.observe.EventBus;
import glassredis.resp.RespProtocolException;
import glassredis.resp.RespReader;
import glassredis.resp.RespValue;
import glassredis.resp.RespWriter;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;

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
    private final CommandLoop commandLoop;
    private final long id;
    private final EventBus events;

    Connection(Socket socket, CommandRegistry registry, CommandLoop commandLoop, long id, EventBus events) {
        this.socket = socket;
        this.registry = registry;
        this.commandLoop = commandLoop;
        this.id = id;
        this.events = events;
    }

    @Override
    public void run() {
        String peer = String.valueOf(socket.getRemoteSocketAddress());
        if (events.enabled()) {
            events.publish(new Event.ClientConnected(id, peer));
        }
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
        } finally {
            // 어떤 경로로 빠져나가든(정상 종료, 오류, 서버 종료로 인한 인터럽트) 한 번은 알린다.
            // 대시보드의 접속 목록에 유령이 남지 않으려면 이 자리가 finally 여야 한다.
            if (events.enabled()) {
                events.publish(new Event.ClientDisconnected(id));
            }
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
                reply = Errors.unknownCommand(argv.get(0), argv.subList(1, argv.size()));
            } else {
                // 실행은 직접 하지 않고 실행 스레드에 맡긴 뒤 답을 기다린다.
                // 응답을 받기 전에는 다음 명령을 읽지 않으므로, 파이프라이닝으로 몰아 보낸 명령도
                // 보낸 순서대로 실행되고 응답도 그 순서로 나간다.
                try {
                    reply = commandLoop.submit(command, argv.subList(1, argv.size()), id).get();
                } catch (InterruptedException e) {
                    // 서버가 종료하면서 커넥션 스레드를 깨웠다.
                    Thread.currentThread().interrupt();
                    return;
                } catch (ExecutionException e) {
                    // 실행 스레드가 예외를 에러 응답으로 바꿔 채우므로 여기 올 일은 없어야 한다.
                    log("명령 %s 의 응답을 받지 못했습니다: %s", name, e.getCause());
                    reply = Errors.internal(String.valueOf(e.getCause()));
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
