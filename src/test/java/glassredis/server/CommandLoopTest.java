package glassredis.server;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.observe.Event;
import glassredis.observe.EventBuffer;
import glassredis.observe.EventHub;
import glassredis.observe.EventBus;
import glassredis.observe.EventRecord;
import glassredis.resp.RespValue;
import glassredis.store.Entry;
import glassredis.store.Key;
import glassredis.store.Keyspace;
import glassredis.store.ManualClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(10)
class CommandLoopTest {

    private CommandLoop loop;

    @BeforeEach
    void startLoop() {
        loop = new CommandLoop(new Keyspace(), EventBus.NONE, () -> {
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
        Command recordThread = new StubCommand(ctx -> {
            executedOn.add(Thread.currentThread());
            return RespValue.OK;
        });

        // 가상 스레드 1000개가 동시에 명령을 보낸다. 커넥션 1000개가 동시에 들어온 상황과 같다.
        try (ExecutorService clients = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<RespValue>> replies = new ArrayList<>();
            for (int i = 0; i < 1000; i++) {
                replies.add(clients.submit(() -> loop.submit(recordThread, List.of(), 1).get()));
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
        Command broken = new StubCommand(ctx -> {
            throw new IllegalStateException("boom");
        });
        Command healthy = new StubCommand(ctx -> RespValue.OK);

        assertEquals(new RespValue.Err("ERR internal error: IllegalStateException"),
                loop.submit(broken, List.of(), 1).get());
        assertEquals(RespValue.OK, loop.submit(healthy, List.of(), 1).get());
    }

    @Test
    @DisplayName("주기적 샘플링이 돌아서, 아무도 읽지 않는 만료 키도 결국 지워진다")
    void activeExpiryRunsWithoutReads() throws Exception {
        ManualClock clock = new ManualClock(1_000_000);
        Keyspace keyspace = new Keyspace(clock);
        for (int i = 0; i < 100; i++) {
            keyspace.put(Key.of("k" + i), new Entry("v".getBytes(StandardCharsets.UTF_8), clock.millis() + 10));
        }
        clock.advanceMillis(11);
        // 실행 스레드가 시작하기 전이라 여기서 키스페이스를 직접 채워도 된다.

        CommandLoop expiringLoop = new CommandLoop(keyspace, EventBus.NONE, () -> {
        });
        expiringLoop.start();
        try {
            // 크기도 실행 스레드를 거쳐 읽는다. 테스트 스레드가 키스페이스를 직접 읽으면 그 자체가 규칙 위반이다.
            // 크기를 세는 건 get() 을 부르지 않으므로, 줄어든다면 샘플링이 지운 것이다.
            Command size = new StubCommand(ctx -> new RespValue.Int(ctx.keyspace().size()));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!expiringLoop.submit(size, List.of(), 1).get().equals(new RespValue.Int(0))) {
                assertTrue(System.nanoTime() < deadline, "5초 안에 샘플링이 만료 키를 치워야 한다");
                Thread.sleep(20);
            }
        } finally {
            expiringLoop.close();
        }
    }

    @Test
    @DisplayName("관측을 켜면 실행한 명령이 이벤트로 나온다")
    void publishesExecutedCommands() throws Exception {
        EventHub hub = new EventHub();
        EventBuffer screen = hub.subscribe();
        CommandLoop observed = new CommandLoop(new Keyspace(), hub, () -> {
        });
        observed.start();
        try {
            Command stub = new StubCommand(ctx -> RespValue.OK);
            List<byte[]> args = List.of("key".getBytes(StandardCharsets.UTF_8));

            assertEquals(RespValue.OK, observed.submit(stub, args, 7).get());

            // 이벤트는 응답을 채우기 전에 발행된다. 그래서 get() 이 돌아온 시점에는 이미 버퍼에 들어 있다.
            List<EventRecord> drained = screen.drain(10);
            assertEquals(1, drained.size());
            Event.CommandExecuted executed = (Event.CommandExecuted) drained.get(0).event();
            assertEquals(7L, executed.connectionId());
            assertEquals("STUB", executed.name());
            assertEquals("key", executed.arguments());
            assertEquals("+OK", executed.reply());
            assertTrue(executed.durationNanos() >= 0);
        } finally {
            observed.close();
        }
    }

    /** 실행할 내용을 람다로 받는 테스트용 명령. */
    private record StubCommand(Function<Context, RespValue> body) implements Command {

        @Override
        public String name() {
            return "STUB";
        }

        @Override
        public RespValue execute(Context ctx, List<byte[]> args) {
            return body.apply(ctx);
        }
    }
}
