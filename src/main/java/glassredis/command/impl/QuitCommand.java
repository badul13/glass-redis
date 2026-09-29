package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.resp.RespValue;

import java.util.List;

/** QUIT - +OK 전송 후 커넥션 종료 */
public final class QuitCommand implements Command {

    @Override
    public String name() {
        return "QUIT";
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        return RespValue.OK;
    }

    @Override
    public boolean closesConnection() {
        return true;
    }
}
