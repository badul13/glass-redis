package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Lookup;
import glassredis.resp.RespValue;
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
    public int arity() {
        return -3;
    }

    @Override
    public boolean writes() {
        return true;
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        Key key = new Key(args.get(0));
        Lookup<SortedSetValue> found = Lookup.zsetWrite(ctx, key, new RespValue.Int(0));
        if (found.reply() != null) {
            return found.reply();
        }
        SortedSetValue zset = found.value();

        long removed = 0;
        for (int i = 1; i < args.size(); i++) {
            if (zset.remove(new Key(args.get(i)))) {
                removed++;
            }
        }
        ctx.dirty(removed);
        if (zset.size() == 0) {
            ctx.keyspace().delete(key);
        }
        return new RespValue.Int(removed);
    }
}
