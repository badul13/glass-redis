package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.resp.RespValue;

import java.util.List;

/** DBSIZE - dictSize 그대로, 만료됐지만 미삭제 키 포함 */
public final class DbsizeCommand implements Command {

    @Override
    public String name() {
        return "DBSIZE";
    }

    @Override
    public int arity() {
        return 1;
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        if (!args.isEmpty()) {
            return Errors.wrongNumberOfArguments(name());
        }
        return new RespValue.Int(ctx.keyspace().size());
    }
}
