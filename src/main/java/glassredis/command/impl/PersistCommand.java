package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.resp.RespValue;
import glassredis.store.Key;

import java.util.List;

/** PERSIST key - 만료 시각 제거 시 1, 키나 만료 시각 없으면 0 */
public final class PersistCommand implements Command {

    @Override
    public String name() {
        return "PERSIST";
    }

    @Override
    public int arity() {
        return 2;
    }

    @Override
    public boolean writes() {
        return true;
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        // persistCommand - lookupKeyWrite 후 removeExpire
        Key key = new Key(args.get(0));
        if (ctx.keyspace().getWrite(key) == null || !ctx.keyspace().removeExpire(key)) {
            return new RespValue.Int(0);
        }
        ctx.dirty();
        return new RespValue.Int(1);
    }
}
