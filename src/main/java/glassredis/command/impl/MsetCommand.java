package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.resp.RespValue;
import glassredis.store.Entry;
import glassredis.store.Key;

import java.util.List;

/** MSET key value [key value ...] - 옵션 없는 SET처럼 기존 만료 시각 제거 */
public final class MsetCommand implements Command {

    @Override
    public String name() {
        return "MSET";
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        if (args.isEmpty() || args.size() % 2 != 0) {
            return Errors.wrongNumberOfArguments(name());
        }
        for (int i = 0; i < args.size(); i += 2) {
            ctx.keyspace().put(new Key(args.get(i)), Entry.of(args.get(i + 1)));
        }
        return RespValue.OK;
    }
}
