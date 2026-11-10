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
import java.util.ArrayList;
import java.util.Collections;
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

    /**
     * 읽기 한 번에 들어온 명령을 묶어 실행 - readQueryFromClient + processInputBuffer
     * 첫 명령만 소켓 대기, 이후는 버퍼에 완성된 명령이 남아 있는 동안만
     * 응답은 묶음 단위로 쓰고 flush 1회 - 파이프라인이면 명령 N개에 큐 왕복 1회·flush 1회
     */
    private void serve(RespReader reader, RespWriter writer) throws IOException {
        List<CommandLoop.Request> batch = new ArrayList<>();
        while (true) {
            batch.clear();
            RespValue protocolError = null;
            boolean closing = false;

            do {
                List<byte[]> argv;
                try {
                    argv = reader.readCommand();
                } catch (RespProtocolException e) {
                    // 다음 명령 경계 불명 - 앞 명령까지 실행 후 에러 응답·종료 (setProtocolError)
                    protocolError = Errors.protocol(e.getMessage());
                    closing = true;
                    break;
                }
                if (argv == null) {
                    // 끊김 - 이미 받은 명령은 실행
                    closing = true;
                    break;
                }
                if (argv.isEmpty()) {
                    continue;
                }
                String name = new String(argv.get(0), StandardCharsets.US_ASCII).toUpperCase(Locale.ROOT);
                Command command = registry.find(name);
                batch.add(new CommandLoop.Request(command, argv));
                // QUIT 뒤 명령은 실행 안 함 - CLIENT_CLOSE_AFTER_REPLY
                if (command != null && command.closesConnection()) {
                    closing = true;
                    break;
                }
            } while (reader.hasBufferedCommand());

            if (!batch.isEmpty()) {
                // 응답 수신 전 다음 읽기 보류 - 파이프라인 순서 보장
                List<RespValue> replies;
                try {
                    replies = commandLoop.submitAll(List.copyOf(batch), id).get();
                } catch (InterruptedException e) {
                    // 서버 종료
                    Thread.currentThread().interrupt();
                    return;
                } catch (ExecutionException e) {
                    // 도달 불가 예상 - 실행 스레드가 예외를 에러 응답으로 변환
                    log("명령 %d개의 응답을 받지 못했습니다: %s", batch.size(), e.getCause());
                    replies = Collections.nCopies(batch.size(), Errors.internal(String.valueOf(e.getCause())));
                }
                for (RespValue reply : replies) {
                    writer.write(reply);
                }
            }
            if (protocolError != null) {
                writer.write(protocolError);
            }
            writer.flush();

            if (closing) {
                return;
            }
        }
    }

    private static void log(String format, Object... args) {
        System.out.println("[glass-redis] " + String.format(format, args));
    }
}
