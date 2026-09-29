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
    @DisplayName("어느 스레드에서 보내든 명령 실행은 실행 스레드 하나에서만")
    void executesOnSingleThread() throws Exception {
        Set<Thread> executedOn = ConcurrentHashMap.newKeySet();
        Command recordThread = new StubCommand(ctx -> {
            executedOn.add(Thread.currentThread());
            return RespValue.OK;
        });

        // 가상 스레드 1000개로 동시 접속 재현
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
    @DisplayName("명령 예외는 에러 응답으로 변환 - 실행 스레드는 계속 동작")
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
    @DisplayName("주기적 샘플링 - 아무도 읽지 않는 만료 키도 결국 삭제")
    void activeExpiryRunsWithoutReads() throws Exception {
        ManualClock clock = new ManualClock(1_000_000);
        Keyspace keyspace = new Keyspace(clock);
        for (int i = 0; i < 100; i++) {
            keyspace.put(Key.of("k" + i),
                    Entry.of("v".getBytes(StandardCharsets.UTF_8)).withExpireAt(clock.millis() + 10));
        }
        clock.advanceMillis(11);
        // 실행 스레드 시작 전이라 키스페이스 직접 채우기 가능

        CommandLoop expiringLoop = new CommandLoop(keyspace, EventBus.NONE, () -> {
        });
        expiringLoop.start();
        try {
            // 크기도 실행 스레드에서 조회 - size() 는 get() 을 부르지 않으므로 줄어든 만큼이 샘플링 삭제분
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
    @DisplayName("관측 활성화 시 실행한 명령이 이벤트로 발행")
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

            // 이벤트는 응답 전에 발행 - get() 이후에는 이미 버퍼에 존재
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
