package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.resp.RespValue;
import glassredis.store.Entry;
import glassredis.store.Key;

import java.util.List;

/** TYPE key - 자료형 이름은 단순 문자열, 키 없으면 none */
public final class TypeCommand implements Command {

    private static final RespValue NONE = new RespValue.SimpleString("none");

    @Override
    public String name() {
        return "TYPE";
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        if (args.size() != 1) {
            return Errors.wrongNumberOfArguments(name());
        }
        Entry entry = ctx.keyspace().get(new Key(args.get(0)));
        return entry == null ? NONE : new RespValue.SimpleString(entry.value().typeName());
    }
}
