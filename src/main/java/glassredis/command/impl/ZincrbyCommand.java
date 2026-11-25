package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.resp.RespValue;

import java.util.List;

/**
 * ZINCRBY key increment member - 결과 점수, 없는 멤버는 0부터
 * Redis 처럼 ZADD INCR 와 같은 경로 (zaddGenericCommand) - 증가량 자리의 NX 등 옵션 단어는 syntax error
 */
public final class ZincrbyCommand implements Command {

    @Override
    public String name() {
        return "ZINCRBY";
    }

    @Override
    public int arity() {
        return 4;
    }

    @Override
    public boolean writes() {
        return true;
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        return ZaddCommand.zadd(ctx, args, true);
    }
}
