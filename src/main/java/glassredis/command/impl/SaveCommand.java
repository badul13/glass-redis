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

    private static final RespValue FAILED = new RespValue.Err("ERR");

    @Override
    public String name() {
        return "SAVE";
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        if (!args.isEmpty()) {
            return Errors.wrongNumberOfArguments(name());
        }
        String failure = ctx.server().save();
        if (failure != null) {
            System.err.println("[glass-redis] RDB 저장 실패: " + failure);
            return FAILED;
        }
        return RespValue.OK;
    }
}
