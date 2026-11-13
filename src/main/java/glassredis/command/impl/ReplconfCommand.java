package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.resp.RespValue;

import java.util.List;

/**
 * REPLCONF <option> <value> ... - 레플리카가 PSYNC 전에 자기 정보를 알림, 이후 ACK 로 오프셋 보고
 * 처리는 복제 쪽 (replconfCommand)
 */
public final class ReplconfCommand implements Command {

    @Override
    public String name() {
        return "REPLCONF";
    }

    @Override
    public int arity() {
        return -1;
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        return ctx.replication().replconf(ctx.client(), args);
    }
}
