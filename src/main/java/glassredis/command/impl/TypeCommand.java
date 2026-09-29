package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.resp.RespValue;
import glassredis.store.Entry;
import glassredis.store.Key;

import java.util.List;

/**
 * {@code TYPE key} — 값의 자료형 이름. 키가 없으면 {@code none}.
 *
 * <p>응답은 벌크 문자열이 아니라 단순 문자열({@code +string})이다. 실제 Redis 가 그렇게 준다.
 */
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
