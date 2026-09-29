package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.command.Numbers;
import glassredis.resp.RespValue;
import glassredis.store.Entry;
import glassredis.store.Key;
import glassredis.store.Keyspace;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;

/**
 * EXPIRE key seconds [NX|XX|GT|LT], PEXPIRE key milliseconds [NX|XX|GT|LT]
 * 키 없음 또는 조건 불일치 시 0, 설정 시 1
 * GT/LT - 만료 없음은 무한대 취급
 * 이미 지난 시각이면 즉시 키 삭제 후 1
 * 검사 순서 - 옵션, 숫자, 키 존재, 옵션 조건 (Redis와 동일)
 */
public final class ExpireCommand implements Command {

    private final String name;
    private final long unitMillis;

    private ExpireCommand(String name, long unitMillis) {
        this.name = name;
        this.unitMillis = unitMillis;
    }

    public static ExpireCommand seconds() {
        return new ExpireCommand("EXPIRE", 1000);
    }

    public static ExpireCommand milliseconds() {
        return new ExpireCommand("PEXPIRE", 1);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        if (args.size() < 2) {
            return Errors.wrongNumberOfArguments(name);
        }

        boolean nx = false;
        boolean xx = false;
        boolean gt = false;
        boolean lt = false;
        for (int i = 2; i < args.size(); i++) {
            switch (new String(args.get(i), StandardCharsets.US_ASCII).toUpperCase(Locale.ROOT)) {
                case "NX" -> nx = true;
                case "XX" -> xx = true;
                case "GT" -> gt = true;
                case "LT" -> lt = true;
                default -> {
                    return Errors.unsupportedOption(args.get(i));
                }
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
            expireAt = Math.addExact(now, Math.multiplyExact(amount.getAsLong(), unitMillis));
        } catch (ArithmeticException overflow) {
            return Errors.invalidExpireTime(name);
        }

        Key key = new Key(args.get(0));
        Entry entry = keyspace.get(key);
        if (entry == null) {
            return new RespValue.Int(0);
        }

        boolean hasExpiry = entry.hasExpiry();
        long current = entry.expireAtMillis();
        boolean conditionFailed = (nx && hasExpiry)
                || (xx && !hasExpiry)
                || (gt && (!hasExpiry || expireAt <= current))
                || (lt && hasExpiry && expireAt >= current);
        if (conditionFailed) {
            return new RespValue.Int(0);
        }

        if (expireAt <= now) {
            keyspace.remove(key);
        } else {
            keyspace.put(key, entry.withExpireAt(expireAt));
        }
        return new RespValue.Int(1);
    }
}
