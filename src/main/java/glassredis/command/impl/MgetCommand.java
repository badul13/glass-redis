package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.resp.RespValue;
import glassredis.store.Entry;
import glassredis.store.Key;
import glassredis.store.StringValue;

import java.util.ArrayList;
import java.util.List;

/** MGET key [key ...] - 없는 키, 문자열 아닌 키 모두 nil (WRONGTYPE 없음) */
public final class MgetCommand implements Command {

    @Override
    public String name() {
        return "MGET";
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        if (args.isEmpty()) {
            return Errors.wrongNumberOfArguments(name());
        }
        List<RespValue> values = new ArrayList<>(args.size());
        for (byte[] key : args) {
            Entry entry = ctx.keyspace().get(new Key(key));
            values.add(entry != null && entry.value() instanceof StringValue string
                    ? new RespValue.BulkString(string.bytes())
                    : RespValue.NIL);
        }
        return new RespValue.Array(values);
    }
}
