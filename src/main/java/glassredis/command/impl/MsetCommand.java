package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.resp.RespValue;
import glassredis.store.Key;
import glassredis.store.StringValue;

import java.util.List;

/** MSET key value [key value ...] - 옵션 없는 SET처럼 기존 만료 시각 제거 */
public final class MsetCommand implements Command {

    @Override
    public String name() {
        return "MSET";
    }

    @Override
    public int arity() {
        return -3;
    }

    @Override
    public boolean writes() {
        return true;
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        // 짝 검사 - arity 로 표현 불가
        if (args.size() % 2 != 0) {
            return Errors.wrongNumberOfArguments(name());
        }
        for (int i = 0; i < args.size(); i += 2) {
            // setKey(flags 0) - 키마다 lookupKeyWrite 후 추가·교체, 만료 제거
            ctx.keyspace().setKey(new Key(args.get(i)), StringValue.of(args.get(i + 1)), 0);
        }
        ctx.dirty(args.size() / 2);
        return RespValue.OK;
    }
}
