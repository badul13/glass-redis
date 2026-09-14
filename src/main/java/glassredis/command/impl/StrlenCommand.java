package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.resp.RespValue;
import glassredis.store.Entry;
import glassredis.store.Key;

import java.util.List;

/**
 * {@code STRLEN key} — 값의 길이. 키가 없으면 0.
 *
 * <p>문자 수가 아니라 바이트 수다. {@code "한글"} 은 UTF-8 로 6 바이트라 6 이다.
 */
public final class StrlenCommand implements Command {

    @Override
    public String name() {
        return "STRLEN";
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        if (args.size() != 1) {
            return Errors.wrongNumberOfArguments(name());
        }
        Entry entry = ctx.keyspace().get(new Key(args.get(0)));
        return new RespValue.Int(entry == null ? 0 : entry.value().length);
    }
}
