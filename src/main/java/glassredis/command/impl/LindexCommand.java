package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.command.Numbers;
import glassredis.resp.RespValue;
import glassredis.store.Entry;
import glassredis.store.Key;
import glassredis.store.ListValue;

import java.util.List;
import java.util.OptionalLong;

/** LINDEX key index - 음수는 뒤에서부터, 범위 밖이면 nil, 가까운 쪽 끝에서 탐색 */
public final class LindexCommand implements Command {

    @Override
    public String name() {
        return "LINDEX";
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        if (args.size() != 2) {
            return Errors.wrongNumberOfArguments(name());
        }
        OptionalLong parsed = Numbers.parseLong(args.get(1));
        if (parsed.isEmpty()) {
            return Errors.notAnInteger();
        }

        Entry entry = ctx.keyspace().get(new Key(args.get(0)));
        if (entry == null) {
            return RespValue.NIL;
        }
        if (!(entry.value() instanceof ListValue list)) {
            return Errors.wrongType();
        }

        byte[] element = list.index(parsed.getAsLong());
        return element == null ? RespValue.NIL : new RespValue.BulkString(element);
    }
}
