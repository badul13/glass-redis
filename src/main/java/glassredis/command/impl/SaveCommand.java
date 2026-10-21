package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.resp.RespValue;

import java.util.List;

/**
 * SAVE - 지금 데이터를 dump.rdb 로, 끝날 때까지 다른 명령 대기
 * 실패 응답은 사유 없이 "ERR" (shared.err) - 사유는 서버 로그
 */
public final class SaveCommand implements Command {

    @Override
    public String name() {
        return "SAVE";
    }

    @Override
    public int arity() {
        return 1;
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        if (!args.isEmpty()) {
            return Errors.wrongNumberOfArguments(name());
        }
        return ctx.server().save();
    }
}
