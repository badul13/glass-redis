package glassredis.command.impl;

import glassredis.command.CommandRegistry;
import glassredis.command.Context;
import glassredis.resp.RespValue;
import glassredis.store.Keyspace;
import glassredis.store.ManualClock;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 소켓 없이 명령 직접 실행 - 시계는 수동 조작, 전파는 CommandLoop와 같은 순서로 기록 */
final class CommandTester {

    final ManualClock clock = new ManualClock(1_000_000_000L);
    final Keyspace keyspace = new Keyspace(clock);

    private final CommandRegistry registry = CommandRegistry.withBuiltins();
    private final Context ctx = new Context(keyspace);

    /** AOF로 갈 명령 - 공백으로 이은 문자열, 트랜잭션은 MULTI·EXEC 포함 */
    final List<String> propagated = new ArrayList<>();

    CommandTester() {
        keyspace.setExpiredListener(key -> {
            List<byte[]> del = List.of("DEL".getBytes(StandardCharsets.US_ASCII), key.bytes());
            if (ctx.propagation().active()) {
                ctx.propagation().also(del);
            } else {
                record(List.of(del));
            }
        });
    }

    RespValue run(String... argv) {
        List<byte[]> all = new ArrayList<>();
        for (String arg : argv) {
            all.add(arg.getBytes(StandardCharsets.UTF_8));
        }
        ctx.propagation().begin(all);
        RespValue reply = registry.find(argv[0].toUpperCase(Locale.ROOT)).execute(ctx, all.subList(1, all.size()));
        record(ctx.propagation().finish());
        return reply;
    }

    private void record(List<List<byte[]>> commands) {
        boolean transaction = commands.size() > 1;
        if (transaction) {
            propagated.add("MULTI");
        }
        for (List<byte[]> command : commands) {
            List<String> parts = new ArrayList<>();
            command.forEach(arg -> parts.add(new String(arg, StandardCharsets.UTF_8)));
            propagated.add(String.join(" ", parts));
        }
        if (transaction) {
            propagated.add("EXEC");
        }
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
