package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.resp.RespValue;
import glassredis.store.Key;

import java.util.List;

/**
 * {@code EXISTS key [key ...]} — 존재하는 키의 개수를 돌려준다.
 *
 * <p>같은 키를 여러 번 적으면 그만큼 여러 번 센다. {@code EXISTS k k} 는 k 가 있으면 2 다.
 * 직관과 다르지만 Redis 문서에 명시된 동작이다.
 */
public final class ExistsCommand implements Command {

    @Override
    public String name() {
        return "EXISTS";
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        if (args.isEmpty()) {
            return Errors.wrongNumberOfArguments(name());
        }
        long count = 0;
        for (byte[] key : args) {
            if (ctx.keyspace().get(new Key(key)) != null) {
                count++;
            }
        }
        return new RespValue.Int(count);
    }
}
