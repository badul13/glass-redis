package glassredis.command.impl;

import glassredis.command.CommandRegistry;
import glassredis.command.Context;
import glassredis.resp.RespValue;
import glassredis.store.Keyspace;
import glassredis.store.ManualClock;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 소켓 없이 명령 직접 실행 - 시계는 수동 조작 */
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

    /** 벌크 문자열 배열 */
    static RespValue array(String... texts) {
        List<RespValue> items = new ArrayList<>();
        for (String text : texts) {
            items.add(bulk(text));
        }
        return new RespValue.Array(items);
    }

    static RespValue integer(long value) {
        return new RespValue.Int(value);
    }

    static RespValue error(String message) {
        return new RespValue.Err(message);
    }
}
