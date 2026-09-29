package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.resp.RespValue;
import glassredis.store.Key;

import java.util.List;

/** EXISTS key [key ...] - 같은 키 반복 시 중복 집계 */
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
