package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.command.Numbers;
import glassredis.resp.RespValue;

import java.util.List;
import java.util.OptionalLong;

/**
 * SELECT <index> - selectCommand
 * 이 서버는 DB 가 하나 (databases 1) - 0 만 받음, 복제 스트림·AOF 앞의 SELECT 0 을 위해 있음
 */
public final class SelectCommand implements Command {

    /** server.dbnum */
    public static final int DATABASES = 1;

    @Override
    public String name() {
        return "SELECT";
    }

    @Override
    public boolean allowedWhileLoading() {
        return true;
    }

    @Override
    public int arity() {
        return 2;
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        // getIntFromObjectOrReply(.., NULL) - 정수 아님, int 범위 밖
        OptionalLong index = Numbers.parseLong(args.get(0));
        if (index.isEmpty()) {
            return Errors.notAnInteger();
        }
        if (index.getAsLong() < Integer.MIN_VALUE || index.getAsLong() > Integer.MAX_VALUE) {
            return Errors.outOfIntRange();
        }
        if (index.getAsLong() < 0 || index.getAsLong() >= DATABASES) {
            return Errors.dbIndexOutOfRange();
        }
        return RespValue.OK;
    }
}
