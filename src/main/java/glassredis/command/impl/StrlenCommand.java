package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Lookup;
import glassredis.resp.RespValue;
import glassredis.store.Key;
import glassredis.store.StringValue;

import java.util.List;

/** STRLEN key - 바이트 수, 키 없으면 0 */
public final class StrlenCommand implements Command {

    @Override
    public String name() {
        return "STRLEN";
    }

    @Override
    public int arity() {
        return 2;
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        Lookup<StringValue> found = Lookup.string(ctx, new Key(args.get(0)), new RespValue.Int(0));
        if (found.reply() != null) {
            return found.reply();
        }
        StringValue string = found.value();
        return new RespValue.Int(string.bytes().length);
    }
}
