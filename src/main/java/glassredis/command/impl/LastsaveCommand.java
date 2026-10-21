package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.resp.RespValue;

import java.util.List;

/** LASTSAVE - 마지막 저장 성공 시각 (유닉스 초), 기동 시각은 저장된 것으로 간주 */
public final class LastsaveCommand implements Command {

    @Override
    public String name() {
        return "LASTSAVE";
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
        return new RespValue.Int(ctx.server().lastSave());
    }
}
