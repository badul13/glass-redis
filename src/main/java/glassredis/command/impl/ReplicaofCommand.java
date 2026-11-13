package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.resp.RespValue;

import java.util.List;

/** REPLICAOF <host> <port> | NO ONE - 레플리카로 바꾸거나 마스터로 되돌림 (replicaofCommand), SLAVEOF 는 옛 이름 */
public final class ReplicaofCommand implements Command {

    private final String name;

    private ReplicaofCommand(String name) {
        this.name = name;
    }

    public static ReplicaofCommand replicaof() {
        return new ReplicaofCommand("REPLICAOF");
    }

    public static ReplicaofCommand slaveof() {
        return new ReplicaofCommand("SLAVEOF");
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public int arity() {
        return 3;
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        return ctx.replication().replicaof(ctx.client(), args);
    }
}
