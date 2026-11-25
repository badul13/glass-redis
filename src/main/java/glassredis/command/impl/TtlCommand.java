package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.resp.RespValue;
import glassredis.store.Key;
import glassredis.store.Keyspace;

import java.util.List;

/**
 * TTL key, PTTL key - 키 없으면 -2, 만료 시각 없으면 -1
 * TTL - 초 단위 반올림 (ttl + 500) / 1000, Redis와 동일
 */
public final class TtlCommand implements Command {

    private static final long KEY_MISSING = -2;
    private static final long NO_EXPIRY = -1;

    private final String name;
    private final long unitMillis;

    private TtlCommand(String name, long unitMillis) {
        this.name = name;
        this.unitMillis = unitMillis;
    }

    public static TtlCommand seconds() {
        return new TtlCommand("TTL", 1000);
    }

    public static TtlCommand milliseconds() {
        return new TtlCommand("PTTL", 1);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public int arity() {
        return 2;
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        // ttlGenericCommand - lookupKeyReadWithFlags(LOOKUP_NOTOUCH) 후 getExpire, 기준은 명령 시각 스냅샷
        Keyspace keyspace = ctx.keyspace();
        Key key = new Key(args.get(0));
        if (keyspace.getNoTouch(key) == null) {
            return new RespValue.Int(KEY_MISSING);
        }
        long expire = keyspace.getExpire(key);
        if (expire == -1) {
            return new RespValue.Int(NO_EXPIRY);
        }
        long remainingMillis = Math.max(0, expire - keyspace.now());
        return new RespValue.Int((remainingMillis + unitMillis / 2) / unitMillis);
    }
}
