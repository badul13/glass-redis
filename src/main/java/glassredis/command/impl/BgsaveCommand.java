package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.resp.RespValue;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * BGSAVE [SCHEDULE] - dump.rdb 저장 시작, 완료는 기다리지 않음
 * AOF 재작성 중이면 에러, SCHEDULE 이면 재작성 끝난 뒤로 예약
 */
public final class BgsaveCommand implements Command {

    @Override
    public String name() {
        return "BGSAVE";
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        boolean schedule = false;
        if (!args.isEmpty()) {
            if (args.size() == 1 && new String(args.get(0), StandardCharsets.UTF_8).equalsIgnoreCase("SCHEDULE")) {
                schedule = true;
            } else {
                return Errors.syntaxError();
            }
        }
        return ctx.server().bgsave(schedule);
    }
}
