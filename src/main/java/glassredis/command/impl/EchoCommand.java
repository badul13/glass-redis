package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.resp.RespValue;

import java.util.List;

/** ECHO message */
public final class EchoCommand implements Command {

    @Override
    public String name() {
        return "ECHO";
    }

    @Override
    public int arity() {
        return 2;
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        return new RespValue.BulkString(args.get(0));
    }
}
