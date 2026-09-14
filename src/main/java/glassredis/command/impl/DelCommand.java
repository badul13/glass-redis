package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.resp.RespValue;
import glassredis.store.Key;

import java.util.List;

/**
 * {@code DEL key [key ...]} — 키를 지우고, 실제로 지운 개수를 돌려준다.
 *
 * <p>원래 없던 키는 세지 않는다. 그래서 응답만 보고도 "지웠다"와 "원래 없었다"를 구분할 수 있다.
 */
public final class DelCommand implements Command {

    @Override
    public String name() {
        return "DEL";
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        if (args.isEmpty()) {
            return Errors.wrongNumberOfArguments(name());
        }
        long removed = 0;
        for (byte[] key : args) {
            if (ctx.keyspace().remove(new Key(key))) {
                removed++;
            }
        }
        return new RespValue.Int(removed);
    }
}
