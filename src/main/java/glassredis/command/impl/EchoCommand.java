package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.resp.RespValue;

import java.util.List;

/**
 * {@code ECHO <message>} — 받은 값을 그대로 돌려준다.
 *
 * <p>기능은 시시하지만 왕복 경로 전체(읽기 → 파싱 → 실행 → 직렬화 → 쓰기)를
 * 바이트 손실 없이 통과하는지 확인하는 시험지 역할을 한다.
 */
public final class EchoCommand implements Command {

    @Override
    public String name() {
        return "ECHO";
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        if (args.size() != 1) {
            return Errors.wrongNumberOfArguments(name());
        }
        return new RespValue.BulkString(args.get(0));
    }
}
