package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Lookup;
import glassredis.resp.RespValue;
import glassredis.store.Key;
import glassredis.store.SortedSetValue;

import java.util.List;

/** ZCARD key - 키 없으면 0 */
public final class ZcardCommand implements Command {

    @Override
    public String name() {
        return "ZCARD";
    }

    @Override
    public int arity() {
        return 2;
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        Lookup<SortedSetValue> found = Lookup.zset(ctx, new Key(args.get(0)), new RespValue.Int(0));
        if (found.reply() != null) {
            return found.reply();
        }
        SortedSetValue zset = found.value();
        return new RespValue.Int(zset.size());
    }
}
