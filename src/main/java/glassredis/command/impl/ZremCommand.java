package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.resp.RespValue;
import glassredis.store.Entry;
import glassredis.store.Key;
import glassredis.store.SortedSetValue;

import java.util.List;

/** ZREM key member [member ...] - 비면 키 삭제 */
public final class ZremCommand implements Command {

    @Override
    public String name() {
        return "ZREM";
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        if (args.size() < 2) {
            return Errors.wrongNumberOfArguments(name());
        }
        Key key = new Key(args.get(0));
        Entry entry = ctx.keyspace().get(key);
        if (entry == null) {
            return new RespValue.Int(0);
        }
        if (!(entry.value() instanceof SortedSetValue zset)) {
            return Errors.wrongType();
        }

        long removed = 0;
        for (int i = 1; i < args.size(); i++) {
            if (zset.remove(new Key(args.get(i)))) {
                removed++;
            }
        }
        if (zset.size() == 0) {
            ctx.keyspace().remove(key);
        }
        return new RespValue.Int(removed);
    }
}
