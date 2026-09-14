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
 * {@code SET key value [NX | XX] [GET] [EX seconds | PX milliseconds | EXAT unix-seconds | PXAT unix-milliseconds | KEEPTTL]}
 *
 * <ul>
 *   <li>{@code EX}/{@code PX} — 지금부터 몇 초/밀리초 뒤에 만료. {@code EXAT}/{@code PXAT} — 유닉스 시각으로 만료.</li>
 *   <li>{@code NX} — 키가 <b>없을 때만</b> 쓴다. {@code XX} — 키가 <b>있을 때만</b> 쓴다.
 *       조건이 안 맞아 쓰지 않았으면 {@code OK} 대신 nil 을 준다.</li>
 *   <li>{@code GET} — {@code OK} 대신 쓰기 전의 값(없었으면 nil)을 준다.</li>
 *   <li>{@code KEEPTTL} — 기존 만료 시각을 유지한다.</li>
 * </ul>
 *
 * <p>가장 틀리기 쉬운 지점: 옵션 없는 {@code SET} 은 값만 바꾸는 게 아니라 <b>만료 시각도 지운다</b>.
 * {@code SET k v EX 10} 뒤에 {@code SET k v2} 를 하면 k 는 더 이상 만료되지 않는다.
 * 기존 만료를 유지하려면 {@code KEEPTTL} 을 줘야 한다.
 *
 * <p>옵션 해석은 실제 Redis 와 같은 두 단계다.
 * <ol>
 *   <li>옵션 이름만 훑어서 조합이 맞는지 본다. 막는 건 <b>서로 다른 종류끼리의 충돌</b>뿐이고,
 *       같은 옵션의 반복은 받아준다. {@code EX 10 EX 20} 은 뒤의 20초가 적용된다.</li>
 *   <li>그다음 마지막으로 받은 만료 값 하나만 숫자로 해석한다.</li>
 * </ol>
 * 그래서 {@code SET k v EX ten BOGUS} 는 "정수가 아님"이 아니라 syntax error 이고,
 * {@code SET k v EX ten EX 10} 은 앞의 {@code ten} 을 보지도 않고 성공한다.
 */
public final class SetCommand implements Command {

    /** 만료 옵션 종류. 단위와, 지금부터의 상대 시간인지 절대 시각인지를 함께 담는다. */
    private enum ExpiryOption {
        EX(1000, true),
        PX(1, true),
        EXAT(1000, false),
        PXAT(1, false);

        final long unitMillis;
        final boolean relative;

        ExpiryOption(long unitMillis, boolean relative) {
            this.unitMillis = unitMillis;
            this.relative = relative;
        }
    }

    @Override
    public String name() {
        return "SET";
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        if (args.size() < 2) {
            return Errors.wrongNumberOfArguments(name());
        }

        // 1단계: 옵션 조합만 확인한다.
        boolean nx = false;
        boolean xx = false;
        boolean get = false;
        boolean keepTtl = false;
        ExpiryOption expiry = null;
        byte[] expiryArgument = null;

        for (int i = 2; i < args.size(); i++) {
            String option = new String(args.get(i), StandardCharsets.US_ASCII).toUpperCase(Locale.ROOT);
            boolean hasValue = i + 1 < args.size();
            switch (option) {
                case "NX" -> {
                    if (xx) {
                        return Errors.syntaxError();
                    }
                    nx = true;
                }
                case "XX" -> {
                    if (nx) {
                        return Errors.syntaxError();
                    }
                    xx = true;
                }
                case "GET" -> get = true;
                case "KEEPTTL" -> {
                    if (expiry != null) {
                        return Errors.syntaxError();
                    }
                    keepTtl = true;
                }
                case "EX", "PX", "EXAT", "PXAT" -> {
                    ExpiryOption kind = ExpiryOption.valueOf(option);
                    if (keepTtl || (expiry != null && expiry != kind) || !hasValue) {
                        return Errors.syntaxError();
                    }
                    expiry = kind;
                    expiryArgument = args.get(++i);
                }
                default -> {
                    return Errors.syntaxError();
                }
            }
        }

        // 2단계: 만료 값을 해석한다.
        Keyspace keyspace = ctx.keyspace();
        long expireAt = Entry.NO_EXPIRY;
        if (expiry != null) {
            OptionalLong amount = Numbers.parseLong(expiryArgument);
            if (amount.isEmpty()) {
                return Errors.notAnInteger();
            }
            if (amount.getAsLong() <= 0) {
                return Errors.invalidExpireTime(name());
            }
            try {
                long millis = Math.multiplyExact(amount.getAsLong(), expiry.unitMillis);
                expireAt = expiry.relative ? Math.addExact(keyspace.now(), millis) : millis;
            } catch (ArithmeticException overflow) {
                return Errors.invalidExpireTime(name());
            }
        }

        Key key = new Key(args.get(0));
        Entry previous = keyspace.get(key);
        RespValue previousValue = previous == null ? RespValue.NIL : new RespValue.BulkString(previous.value());

        if ((nx && previous != null) || (xx && previous == null)) {
            // 조건이 안 맞아 쓰지 않는다. GET 을 줬으면 그래도 기존 값은 돌려준다.
            return get ? previousValue : RespValue.NIL;
        }

        if (keepTtl && previous != null) {
            expireAt = previous.expireAtMillis();
        }
        // EXAT/PXAT 로 이미 지난 시각을 줬다면 여기서 저장은 되지만, 다음에 읽는 순간 만료로 확인돼 없는 키로 보인다.
        keyspace.put(key, new Entry(args.get(1), expireAt));
        return get ? previousValue : RespValue.OK;
    }
}
