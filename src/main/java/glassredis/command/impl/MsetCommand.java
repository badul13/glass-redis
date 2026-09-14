package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.resp.RespValue;
import glassredis.store.Entry;
import glassredis.store.Key;

import java.util.List;

/**
 * {@code MSET key value [key value ...]} — 여러 키를 한 번에 쓴다.
 *
 * <p>실행 중에 다른 명령이 끼어들지 않으므로, 다른 클라이언트가 일부만 써진 상태를 보는 일이 없다.
 * 옵션 없는 {@code SET} 과 같아서 기존 만료 시각은 지워진다.
 */
public final class MsetCommand implements Command {

    @Override
    public String name() {
        return "MSET";
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        if (args.isEmpty() || args.size() % 2 != 0) {
            return Errors.wrongNumberOfArguments(name());
        }
        for (int i = 0; i < args.size(); i += 2) {
            ctx.keyspace().put(new Key(args.get(i)), Entry.of(args.get(i + 1)));
        }
        return RespValue.OK;
    }
}
