package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.resp.RespValue;
import glassredis.store.Entry;
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
    public RespValue execute(Context ctx, List<byte[]> args) {
        if (args.size() != 1) {
            return Errors.wrongNumberOfArguments(name);
        }
        Keyspace keyspace = ctx.keyspace();
        // get()보다 시각 먼저 읽기 - 남은 시간 음수 방지
        long now = keyspace.now();
        Entry entry = keyspace.get(new Key(args.get(0)));
        if (entry == null) {
            return new RespValue.Int(KEY_MISSING);
        }
        if (!entry.hasExpiry()) {
            return new RespValue.Int(NO_EXPIRY);
        }
        long remainingMillis = Math.max(0, entry.expireAtMillis() - now);
        return new RespValue.Int((remainingMillis + unitMillis / 2) / unitMillis);
    }
}
