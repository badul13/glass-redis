package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.resp.RespValue;

import java.util.List;

/** ROLE - master, 복제 오프셋, 온라인 레플리카 [ip, 포트, ACK 오프셋] 목록 (roleCommand) */
public final class RoleCommand implements Command {

    @Override
    public String name() {
        return "ROLE";
    }

    @Override
    public int arity() {
        return 1;
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        return ctx.replication().role();
    }
}
