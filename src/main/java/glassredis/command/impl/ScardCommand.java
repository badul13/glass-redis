package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Lookup;
import glassredis.resp.RespValue;
import glassredis.store.Key;
import glassredis.store.SetValue;

import java.util.List;

/** SCARD key - 키 없으면 0 */
public final class ScardCommand implements Command {

    @Override
    public String name() {
        return "SCARD";
    }

    @Override
    public int arity() {
        return 2;
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        Lookup<SetValue> found = Lookup.set(ctx, new Key(args.get(0)), new RespValue.Int(0));
        if (found.reply() != null) {
            return found.reply();
        }
        SetValue set = found.value();
        return new RespValue.Int(set.size());
    }
}
