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

/** 커넥션 하나 - 가상 스레드 1개가 블로킹 읽기-실행-쓰기 반복 */
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
            // Nagle 끔 - 요청-응답에서 수십 ms 지연 유발
            open.setTcpNoDelay(true);

            RespReader reader = new RespReader(open.getInputStream());
            RespWriter writer = new RespWriter(open.getOutputStream());
            serve(reader, writer);
        } catch (IOException e) {
            // 갑작스런 끊김은 흔한 일 - 경고 대신 일반 로그
            log("커넥션 %d (%s) 입출력 종료: %s", id, peer, e.getMessage());
        } finally {
            // 어떤 경로로 끝나도 1회 통보 - 접속 목록 유령 방지
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
                // 다음 명령 경계 불명 - 에러 응답 후 종료
                writer.write(Errors.protocol(e.getMessage()));
                writer.flush();
                return;
            }

            if (argv == null) {
                return;
            }
            if (argv.isEmpty()) {
                continue;
            }

            String name = new String(argv.get(0), StandardCharsets.US_ASCII).toUpperCase(Locale.ROOT);
            Command command = registry.find(name);

            RespValue reply;
            if (command == null) {
                reply = Errors.unknownCommand(argv.get(0), argv.subList(1, argv.size()));
            } else {
                // 응답 수신 전 다음 명령 읽기 보류 - 파이프라인 순서 보장
                try {
                    reply = commandLoop.submit(command, argv.subList(1, argv.size()), id).get();
                } catch (InterruptedException e) {
                    // 서버 종료
                    Thread.currentThread().interrupt();
                    return;
                } catch (ExecutionException e) {
                    // 도달 불가 예상 - 실행 스레드가 예외를 에러 응답으로 변환
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
