package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Errors;
import glassredis.resp.RespValue;

import java.util.List;

/**
 * {@code PING} / {@code PING <message>}
 *
 * <p>응답 타입이 인자 유무에 따라 달라지는 게 재미있는 지점이다.
 * 인자가 없으면 고정된 짧은 ASCII 라 오버헤드가 가장 작은 단순 문자열({@code +PONG})을 쓰고,
 * 인자가 있으면 사용자가 준 임의의 바이트를 그대로 돌려줘야 하므로
 * 바이너리 세이프한 벌크 문자열을 쓴다.
 */
public final class PingCommand implements Command {

    @Override
    public String name() {
        return "PING";
    }

    @Override
    public RespValue execute(List<byte[]> args) {
        return switch (args.size()) {
            case 0 -> RespValue.PONG;
            case 1 -> new RespValue.BulkString(args.get(0));
            default -> Errors.wrongNumberOfArguments(name());
        };
    }
}
