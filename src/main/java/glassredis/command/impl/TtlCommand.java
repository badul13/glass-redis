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
 * {@code TTL key} / {@code PTTL key} — 남은 시간을 초/밀리초로 준다.
 *
 * <p>특수값이 두 개 있다. 키가 없으면 {@code -2}, 키는 있는데 만료 시각이 없으면 {@code -1}.
 *
 * <p>{@code TTL} 은 밀리초를 초로 바꿀 때 버리지 않고 반올림한다. 그래서 {@code SET k v EX 10} 직후의
 * {@code TTL} 은 9 가 아니라 10 이다. 실제 Redis 도 {@code (ttl + 500) / 1000} 으로 계산한다.
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
        // get() 보다 먼저 시각을 찍는다. get() 이 "아직 안 만료"라고 판단한 시각보다 이르거나 같으므로
        // 남은 시간이 음수로 계산되는 일이 없다.
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
