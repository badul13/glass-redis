package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.resp.RespValue;
import glassredis.store.Entry;
import glassredis.store.Key;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code MGET key [key ...]} — 여러 키의 값을 한 번에 읽는다. 없는 키 자리에는 nil 이 들어간다.
 *
 * <p>응답 배열의 순서는 인자 순서와 같다. 그래서 nil 이 섞여 있어도 어느 키가 없었는지 알 수 있다.
 */
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
            values.add(entry == null ? RespValue.NIL : new RespValue.BulkString(entry.value()));
        }
        return new RespValue.Array(values);
    }
}
