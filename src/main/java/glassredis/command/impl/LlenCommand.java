package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Lookup;
import glassredis.resp.RespValue;
import glassredis.store.Key;
import glassredis.store.ListValue;

import java.util.List;

/** LLEN key - 키 없으면 0 */
public final class LlenCommand implements Command {

    @Override
    public String name() {
        return "LLEN";
    }

    @Override
    public int arity() {
        return 2;
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        Lookup<ListValue> found = Lookup.list(ctx, new Key(args.get(0)), new RespValue.Int(0));
        if (found.reply() != null) {
            return found.reply();
        }
        ListValue list = found.value();
        return new RespValue.Int(list.length());
    }
}
