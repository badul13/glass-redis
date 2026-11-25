package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.resp.RespValue;
import glassredis.store.Key;

import java.util.List;

/** DEL key [key ...] - 실제 삭제 개수 */
public final class DelCommand implements Command {

    @Override
    public String name() {
        return "DEL";
    }

    @Override
    public int arity() {
        return -2;
    }

    @Override
    public boolean writes() {
        return true;
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        long removed = 0;
        for (byte[] raw : args) {
            // delGenericCommand - expireIfNeeded 뒤 dbSyncDelete
            // 마스터는 만료 키를 먼저 지워 0, 레플리카는 만료로 안 지우므로 실제 삭제되어 1
            Key key = new Key(raw);
            ctx.keyspace().expireIfNeeded(key);
            if (ctx.keyspace().delete(key)) {
                removed++;
            }
        }
        ctx.dirty(removed);
        return new RespValue.Int(removed);
    }
}
