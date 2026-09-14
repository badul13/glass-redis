package glassredis.server;

import glassredis.command.Command;
import glassredis.resp.RespValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Timeout(10)
class CommandLoopTest {

    private CommandLoop loop;

    @BeforeEach
    void startLoop() {
        loop = new CommandLoop(() -> {
        });
        loop.start();
    }

    @AfterEach
    void stopLoop() {
        loop.close();
    }

    @Test
    @DisplayName("어느 스레드에서 보내든 명령은 실행 스레드 하나에서만 실행된다")
    void executesOnSingleThread() throws Exception {
        Set<Thread> executedOn = ConcurrentHashMap.newKeySet();
        Command recordThread = new StubCommand(args -> {
            executedOn.add(Thread.currentThread());
            return RespValue.OK;
        });

        // 가상 스레드 1000개가 동시에 명령을 보낸다. 커넥션 1000개가 동시에 들어온 상황과 같다.
        try (ExecutorService clients = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<RespValue>> replies = new ArrayList<>();
            for (int i = 0; i < 1000; i++) {
                replies.add(clients.submit(() -> loop.submit(recordThread, List.of()).get()));
            }
            for (Future<RespValue> reply : replies) {
                assertEquals(RespValue.OK, reply.get());
            }
        }

        assertEquals(1, executedOn.size(), "실행된 스레드: " + executedOn);
        assertEquals("glass-redis-executor", executedOn.iterator().next().getName());
    }

    @Test
    @DisplayName("명령이 예외를 던지면 에러 응답으로 바꾸고, 실행 스레드는 계속 돈다")
    void survivesCommandFailure() throws Exception {
        Command broken = new StubCommand(args -> {
            throw new IllegalStateException("boom");
        });
        Command healthy = new StubCommand(args -> RespValue.OK);

        assertEquals(new RespValue.Err("ERR internal error: IllegalStateException"),
                loop.submit(broken, List.of()).get());
        assertEquals(RespValue.OK, loop.submit(healthy, List.of()).get());
    }

    /** 실행할 내용을 람다로 받는 테스트용 명령. */
    private record StubCommand(Function<List<byte[]>, RespValue> body) implements Command {

        @Override
        public String name() {
            return "STUB";
        }

        @Override
        public RespValue execute(List<byte[]> args) {
            return body.apply(args);
        }
    }
}
