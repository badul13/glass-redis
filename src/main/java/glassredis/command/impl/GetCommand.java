package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.resp.RespValue;
import glassredis.store.Entry;
import glassredis.store.Key;

import java.util.List;

/**
 * {@code GET key} — 값을 돌려준다. 키가 없으면 nil.
 *
 * <p>"키가 없음"(nil, {@code $-1}) 과 "값이 빈 문자열"({@code $0}) 은 다른 응답이다.
 * {@code SET k ""} 을 한 뒤의 {@code GET k} 는 nil 이 아니라 빈 문자열을 줘야 한다.
 */
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
        return entry == null ? RespValue.NIL : new RespValue.BulkString(entry.value());
    }
}
