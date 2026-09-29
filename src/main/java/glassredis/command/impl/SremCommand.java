package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.resp.RespValue;
import glassredis.store.Entry;
import glassredis.store.Key;
import glassredis.store.SetValue;

import java.util.List;

/**
 * {@code SREM key member [member ...]} — 원소를 빼고, 실제로 뺀 수를 준다.
 * 마지막 원소가 빠지면 키째로 사라진다.
 */
public final class SremCommand implements Command {

    @Override
    public String name() {
        return "SREM";
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
        if (!(entry.value() instanceof SetValue set)) {
            return Errors.wrongType();
        }

        long removed = 0;
        for (int i = 1; i < args.size(); i++) {
            if (set.members().remove(new Key(args.get(i)))) {
                removed++;
            }
        }
        if (set.size() == 0) {
            ctx.keyspace().remove(key);
        }
        return new RespValue.Int(removed);
    }
}
