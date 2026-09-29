package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.resp.RespValue;

import java.util.List;

/** PING [message] - 인자 없으면 단순 문자열 +PONG, 있으면 벌크 문자열로 에코 */
public final class PingCommand implements Command {

    @Override
    public String name() {
        return "PING";
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        return switch (args.size()) {
            case 0 -> RespValue.PONG;
            case 1 -> new RespValue.BulkString(args.get(0));
            default -> Errors.wrongNumberOfArguments(name());
        };
    }
}
