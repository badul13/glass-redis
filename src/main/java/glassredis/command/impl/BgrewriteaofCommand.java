package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.resp.RespValue;

import java.util.List;

/**
 * BGREWRITEAOF - AOF를 지금 데이터 기준 최소 명령으로 다시 쓰기 시작, 완료는 기다리지 않음
 * 진행 중이면 에러
 */
public final class BgrewriteaofCommand implements Command {

    private static final RespValue STARTED =
            new RespValue.SimpleString("Background append only file rewriting started");

    @Override
    public String name() {
        return "BGREWRITEAOF";
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        if (!args.isEmpty()) {
            return Errors.wrongNumberOfArguments(name());
        }
        String failure = ctx.server().rewriteAppendOnlyFile();
        return failure == null ? STARTED : new RespValue.Err("ERR " + failure);
    }
}
