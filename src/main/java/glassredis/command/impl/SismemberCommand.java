package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.resp.RespValue;
import glassredis.store.Entry;
import glassredis.store.Key;
import glassredis.store.SetValue;

import java.util.List;

/** {@code SISMEMBER key member} — 원소가 있으면 1, 없으면 0. 해시로 찾으므로 Set 이 아무리 커도 O(1) 이다. */
public final class SismemberCommand implements Command {

    @Override
    public String name() {
        return "SISMEMBER";
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        if (args.size() != 2) {
            return Errors.wrongNumberOfArguments(name());
        }
        Entry entry = ctx.keyspace().get(new Key(args.get(0)));
        if (entry == null) {
            return new RespValue.Int(0);
        }
        if (!(entry.value() instanceof SetValue set)) {
            return Errors.wrongType();
        }
        return new RespValue.Int(set.members().contains(new Key(args.get(1))) ? 1 : 0);
    }
}
