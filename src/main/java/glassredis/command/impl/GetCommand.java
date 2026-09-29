package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.resp.RespValue;
import glassredis.store.Entry;
import glassredis.store.Key;
import glassredis.store.StringValue;

import java.util.List;

/** GET key - 키 없으면 nil */
public final class GetCommand implements Command {

    @Override
    public String name() {
        return "GET";
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        if (args.size() != 1) {
            return Errors.wrongNumberOfArguments(name());
        }
        Entry entry = ctx.keyspace().get(new Key(args.get(0)));
        if (entry == null) {
            return RespValue.NIL;
        }
        if (!(entry.value() instanceof StringValue string)) {
            return Errors.wrongType();
        }
        return new RespValue.BulkString(string.bytes());
    }
}
