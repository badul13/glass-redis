package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.resp.RespValue;

import java.util.ArrayList;
import java.util.List;

/**
 * SYNC, PSYNC <replid> <offset> - 이 연결을 레플리카로 바꿈 (syncCommand)
 * 성공하면 응답 대신 +FULLRESYNC·+CONTINUE 를 복제 쪽이 소켓에 직접 씀
 */
public final class SyncCommand implements Command {

    private final String name;
    private final int arity;

    private SyncCommand(String name, int arity) {
        this.name = name;
        this.arity = arity;
    }

    public static SyncCommand sync() {
        return new SyncCommand("SYNC", 1);
    }

    public static SyncCommand psync() {
        return new SyncCommand("PSYNC", -3);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public int arity() {
        return arity;
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        List<byte[]> argv = new ArrayList<>(args.size() + 1);
        argv.add(name.getBytes());
        argv.addAll(args);
        return ctx.replication().sync(ctx.client(), argv);
    }
}
