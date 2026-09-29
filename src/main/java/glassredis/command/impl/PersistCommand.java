package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.resp.RespValue;
import glassredis.store.Entry;
import glassredis.store.Key;

import java.util.List;

/** PERSIST key - 만료 시각 제거 시 1, 키나 만료 시각 없으면 0 */
public final class PersistCommand implements Command {

    @Override
    public String name() {
        return "PERSIST";
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        if (args.size() != 1) {
            return Errors.wrongNumberOfArguments(name());
        }
        Key key = new Key(args.get(0));
        Entry entry = ctx.keyspace().get(key);
        if (entry == null || !entry.hasExpiry()) {
            return new RespValue.Int(0);
        }
        ctx.keyspace().put(key, entry.withExpireAt(Entry.NO_EXPIRY));
        return new RespValue.Int(1);
    }
}
