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
 * {@code EXPIRE key seconds [NX | XX | GT | LT]} / {@code PEXPIRE key milliseconds [NX | XX | GT | LT]}
 *
 * <p>키가 없으면 0, 걸었으면 1 을 준다. 이미 만료 시각이 있으면 새 값으로 바꾼다.
 *
 * <p>옵션은 "언제 바꿀지"에 대한 조건이다. 조건이 안 맞으면 아무것도 바꾸지 않고 0 을 준다.
 * <ul>
 *   <li>{@code NX} — 만료 시각이 없을 때만. {@code XX} — 있을 때만.</li>
 *   <li>{@code GT} — 새 시각이 기존보다 늦을 때만. {@code LT} — 이를 때만.</li>
 * </ul>
 * GT/LT 에서 "만료 시각 없음"은 무한대로 친다. 그래서 만료 없는 키에 GT 는 항상 실패하고 LT 는 항상 통과한다.
 *
 * <p>0 이나 음수를 주면 "이미 지난 시각에 만료"라는 뜻이라 그 자리에서 키를 지우고 1 을 준다.
 * 어차피 없는 키로 보일 텐데 샘플링이 치울 때까지 메모리에 남겨둘 이유가 없다.
 *
 * <p>검사 순서도 실제 Redis 를 따른다: 옵션 → 숫자 → 키 존재 → 옵션 조건.
 * 그래서 없는 키에 {@code EXPIRE k ten FOO} 를 보내면 0 이 아니라 {@code Unsupported option FOO} 가 나온다.
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
