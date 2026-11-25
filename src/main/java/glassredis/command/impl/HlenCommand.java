package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Lookup;
import glassredis.resp.RespValue;
import glassredis.store.HashValue;
import glassredis.store.Key;

import java.util.List;

/** HLEN key - 키 없으면 0 */
public final class HlenCommand implements Command {

    @Override
    public String name() {
        return "HLEN";
    }

    @Override
    public int arity() {
        return 2;
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        Lookup<HashValue> found = Lookup.hash(ctx, new Key(args.get(0)), new RespValue.Int(0));
        if (found.reply() != null) {
            return found.reply();
        }
        HashValue hash = found.value();
        return new RespValue.Int(hash.size());
    }
}
