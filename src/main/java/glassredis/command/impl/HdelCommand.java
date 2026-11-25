package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Lookup;
import glassredis.resp.RespValue;
import glassredis.store.HashValue;
import glassredis.store.Key;

import java.util.List;

/** HDEL key field [field ...] - 비면 키 삭제 */
public final class HdelCommand implements Command {

    @Override
    public String name() {
        return "HDEL";
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
        Lookup<HashValue> found = Lookup.hashWrite(ctx, key, new RespValue.Int(0));
        if (found.reply() != null) {
            return found.reply();
        }
        HashValue hash = found.value();

        long removed = 0;
        for (int i = 1; i < args.size(); i++) {
            if (hash.delete(new Key(args.get(i)))) {
                removed++;
            }
        }
        ctx.dirty(removed);
        if (hash.size() == 0) {
            ctx.keyspace().delete(key);
        }
        return new RespValue.Int(removed);
    }
}
