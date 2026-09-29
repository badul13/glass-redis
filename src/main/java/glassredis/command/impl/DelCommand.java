package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.resp.RespValue;
import glassredis.store.Key;

import java.util.List;

/** DEL key [key ...] - 실제 삭제 개수 */
public final class DelCommand implements Command {

    @Override
    public String name() {
        return "DEL";
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        if (args.isEmpty()) {
            return Errors.wrongNumberOfArguments(name());
        }
        long removed = 0;
        for (byte[] key : args) {
            if (ctx.keyspace().remove(new Key(key))) {
                removed++;
            }
        }
        return new RespValue.Int(removed);
    }
}
