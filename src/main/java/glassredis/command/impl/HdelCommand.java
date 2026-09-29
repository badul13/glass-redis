package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.resp.RespValue;
import glassredis.store.Entry;
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
    public RespValue execute(Context ctx, List<byte[]> args) {
        if (args.size() < 2) {
            return Errors.wrongNumberOfArguments(name());
        }
        Key key = new Key(args.get(0));
        Entry entry = ctx.keyspace().get(key);
        if (entry == null) {
            return new RespValue.Int(0);
        }
        if (!(entry.value() instanceof HashValue hash)) {
            return Errors.wrongType();
        }

        long removed = 0;
        for (int i = 1; i < args.size(); i++) {
            if (hash.delete(new Key(args.get(i)))) {
                removed++;
            }
        }
        if (hash.size() == 0) {
            ctx.keyspace().remove(key);
        }
        return new RespValue.Int(removed);
    }
}
