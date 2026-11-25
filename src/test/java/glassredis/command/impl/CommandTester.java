package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.CommandRegistry;
import glassredis.command.Context;
import glassredis.command.Propagation;
import glassredis.command.Replication;
import glassredis.command.ServerControl;
import glassredis.resp.RespValue;
import glassredis.store.Keyspace;
import glassredis.store.ManualClock;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 소켓 없이 명령 직접 실행 - 시계는 수동 조작
 * 인자 수 검사·전파 묶기는 CommandLoop 와 같은 공용 함수 사용 (Command.checkArity, Propagation.emit)
 */
final class CommandTester {

    final ManualClock clock = new ManualClock(1_000_000_000L);
    final Keyspace keyspace = new Keyspace(clock);

    private final CommandRegistry registry = CommandRegistry.withBuiltins();
    private final Context ctx;

    /** AOF로 갈 명령 - 공백으로 이은 문자열, 트랜잭션은 MULTI·EXEC 포함 */
    final List<String> propagated = new ArrayList<>();

    /** 마지막 명령의 변경 횟수 - server.dirty 증가량 */
    long lastDirty;

    CommandTester() {
        this(ServerControl.NONE);
    }

    /** @param server SAVE·BGSAVE 등 서버 동작 대역 */
    CommandTester(ServerControl server) {
        ctx = new Context(keyspace, new Propagation(), server, Replication.NONE);
        keyspace.setExpiredListener(key -> {
            List<byte[]> del = List.of("DEL".getBytes(StandardCharsets.UTF_8), key.bytes());
            if (ctx.propagation().active()) {
                ctx.propagation().also(del);
            } else {
                record(List.of(del));
            }
        });
    }

    RespValue run(String... argv) {
        return runBytes(Arrays.stream(argv).map(arg -> arg.getBytes(StandardCharsets.UTF_8)).toArray(byte[][]::new));
    }

    /** NUL 등 임의 바이트 인자용 */
    RespValue runBytes(byte[]... argv) {
        List<byte[]> all = List.of(argv);
        Command command = registry.find(new String(argv[0], StandardCharsets.ISO_8859_1));
        lastDirty = 0;
        RespValue arityError = Command.checkArity(command, all.size());
        if (arityError != null) {
            return arityError;
        }
        // call() 의 enterExecutionUnit - 명령 시작 시각 고정
        keyspace.updateCommandTime();
        ctx.propagation().begin(all);
        RespValue reply = command.execute(ctx, all.subList(1, all.size()));
        lastDirty = ctx.propagation().dirtyCount();
        record(ctx.propagation().finish());
        return reply;
    }

    private void record(List<List<byte[]>> commands) {
        Propagation.emit(commands, (dbid, command) -> {
            List<String> parts = new ArrayList<>();
            command.forEach(arg -> parts.add(new String(arg, StandardCharsets.UTF_8)));
            propagated.add(String.join(" ", parts));
        });
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
