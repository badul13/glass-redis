package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Lookup;
import glassredis.resp.RespValue;
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
    public int arity() {
        return 2;
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        Lookup<StringValue> found = Lookup.string(ctx, new Key(args.get(0)), RespValue.NIL);
        if (found.reply() != null) {
            return found.reply();
        }
        StringValue string = found.value();
        return new RespValue.BulkString(string.bytes());
    }
}
