package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.resp.RespValue;
import glassredis.store.Entry;
import glassredis.store.Key;
import glassredis.store.SetValue;

import java.util.List;

/** {@code SCARD key} — 원소 수. 키가 없으면 0. */
public final class ScardCommand implements Command {

    @Override
    public String name() {
        return "SCARD";
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        if (args.size() != 1) {
            return Errors.wrongNumberOfArguments(name());
        }
        Entry entry = ctx.keyspace().get(new Key(args.get(0)));
        if (entry == null) {
            return new RespValue.Int(0);
        }
        if (!(entry.value() instanceof SetValue set)) {
            return Errors.wrongType();
        }
        return new RespValue.Int(set.size());
    }
}
