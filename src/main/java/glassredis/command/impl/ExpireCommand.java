package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.command.Numbers;
import glassredis.command.Options;
import glassredis.resp.RespValue;
import glassredis.store.Key;
import glassredis.store.Keyspace;

import java.util.List;
import java.util.OptionalLong;

/**
 * EXPIRE key seconds, PEXPIRE key ms, EXPIREAT key unix-s, PEXPIREAT key unix-ms - 뒤에 [NX|XX|GT|LT]
 * 키 없음 또는 조건 불일치 시 0, 설정 시 1
 * GT/LT - 만료 없음은 무한대 취급
 * 이미 지난 시각이면 즉시 키 삭제 후 1 - 로딩 중·레플리카는 삭제 없이 시각만 저장 (checkAlreadyExpired)
 * AOF에는 PEXPIREAT(절대 ms)으로, 이미 지난 시각이면 DEL로
 * 검사 순서 - 옵션, 숫자, 키 존재, 옵션 조건 (Redis와 동일)
 */
public final class ExpireCommand implements Command {

    private final String name;
    private final long unitMillis;
    /** AT 계열 - 기준 시각 0 */
    private final boolean absolute;
    /** PEXPIREAT - 받은 그대로 전파 (c->cmd->proc == pexpireatCommand) */
    private final boolean propagatesAsIs;

    private ExpireCommand(String name, long unitMillis, boolean absolute, boolean propagatesAsIs) {
        this.name = name;
        this.unitMillis = unitMillis;
        this.absolute = absolute;
        this.propagatesAsIs = propagatesAsIs;
    }

    public static ExpireCommand seconds() {
        return new ExpireCommand("EXPIRE", 1000, false, false);
    }

    public static ExpireCommand milliseconds() {
        return new ExpireCommand("PEXPIRE", 1, false, false);
    }

    public static ExpireCommand secondsAt() {
        return new ExpireCommand("EXPIREAT", 1000, true, false);
    }

    public static ExpireCommand millisecondsAt() {
        return new ExpireCommand("PEXPIREAT", 1, true, true);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public int arity() {
        return -3;
    }

    @Override
    public boolean writes() {
        return true;
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        boolean nx = false;
        boolean xx = false;
        boolean gt = false;
        boolean lt = false;
        for (int i = 2; i < args.size(); i++) {
            byte[] option = args.get(i);
            if (Options.is(option, "NX")) {
                nx = true;
            } else if (Options.is(option, "XX")) {
                xx = true;
            } else if (Options.is(option, "GT")) {
                gt = true;
            } else if (Options.is(option, "LT")) {
                lt = true;
            } else {
                return Errors.unsupportedOption(option);
            }
        }
        if (nx && (xx || gt || lt)) {
            return Errors.expireNxNotCompatible();
        }
        if (gt && lt) {
            return Errors.expireGtLtNotCompatible();
        }

        OptionalLong amount = Numbers.parseLong(args.get(1));
        if (amount.isEmpty()) {
            return Errors.notAnInteger();
        }

        Keyspace keyspace = ctx.keyspace();
        long now = keyspace.now();
        long expireAt;
        try {
            expireAt = Math.addExact(absolute ? 0 : now, Math.multiplyExact(amount.getAsLong(), unitMillis));
        } catch (ArithmeticException overflow) {
            return Errors.invalidExpireTime(name);
        }

        // expireGenericCommand - lookupKeyWrite, 조건 옵션이 있을 때만 getExpire
        Key key = new Key(args.get(0));
        if (keyspace.getWrite(key) == null) {
            return new RespValue.Int(0);
        }

        long current = nx || xx || gt || lt ? keyspace.getExpire(key) : -1;
        boolean hasExpiry = current != -1;
        boolean conditionFailed = (nx && hasExpiry)
                || (xx && !hasExpiry)
                || (gt && (!hasExpiry || expireAt <= current))
                || (lt && hasExpiry && expireAt >= current);
        if (conditionFailed) {
            return new RespValue.Int(0);
        }

        // checkAlreadyExpired - 로딩 중이거나 레플리카면 지난 시각도 저장, 삭제는 로딩 뒤 만료 처리·마스터의 DEL 이 맡음
        if (expireAt <= now && !keyspace.loading() && !keyspace.replica()) {
            keyspace.delete(key);
            ctx.dirty();
            ctx.propagateAs("DEL", args.get(0));
        } else {
            // 음수 시각은 손으로 쓴 AOF에서만 - 이미 지난 0으로
            keyspace.setExpire(key, Math.max(0, expireAt));
            ctx.dirty();
            if (!propagatesAsIs) {
                ctx.propagateAs(asPexpireat(args, expireAt));
            }
        }
        return new RespValue.Int(1);
    }

    /** PEXPIREAT key 절대ms + 받은 옵션 그대로 - 이름과 시각만 교체 */
    private static Object[] asPexpireat(List<byte[]> args, long expireAt) {
        Object[] argv = new Object[args.size() + 1];
        argv[0] = "PEXPIREAT";
        argv[1] = args.get(0);
        argv[2] = expireAt;
        for (int i = 2; i < args.size(); i++) {
            argv[i + 1] = args.get(i);
        }
        return argv;
    }
}
