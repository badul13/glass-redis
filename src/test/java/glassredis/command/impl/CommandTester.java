package glassredis.command.impl;

import glassredis.command.CommandRegistry;
import glassredis.command.Context;
import glassredis.resp.RespValue;
import glassredis.store.Keyspace;
import glassredis.store.ManualClock;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 명령을 소켓 없이 직접 실행하는 테스트 도우미.
 *
 * <p>명령은 "Context 와 인자를 받아 응답을 돌려주는 함수"라서 서버를 띄우지 않아도 시험할 수 있다.
 * 시계는 손으로 돌리므로 만료도 {@code sleep} 없이 확인한다.
 */
final class CommandTester {

    final ManualClock clock = new ManualClock(1_000_000_000L);
    final Keyspace keyspace = new Keyspace(clock);

    private final CommandRegistry registry = CommandRegistry.withBuiltins();
    private final Context ctx = new Context(keyspace);

    RespValue run(String... argv) {
        List<byte[]> args = new ArrayList<>();
        for (int i = 1; i < argv.length; i++) {
            args.add(argv[i].getBytes(StandardCharsets.UTF_8));
        }
        return registry.find(argv[0]).execute(ctx, args);
    }

    static RespValue bulk(String text) {
        return RespValue.BulkString.of(text);
    }

    static RespValue integer(long value) {
        return new RespValue.Int(value);
    }

    static RespValue error(String message) {
        return new RespValue.Err(message);
    }
}
