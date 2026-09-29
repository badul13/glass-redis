package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.command.Numbers;
import glassredis.resp.RespValue;
import glassredis.store.Entry;
import glassredis.store.Key;
import glassredis.store.Keyspace;
import glassredis.store.StringValue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;

/**
 * SET key value [NX|XX] [GET] [EX s|PX ms|EXAT unix-s|PXAT unix-ms|KEEPTTL]
 * NX/XX 조건 불일치 시 nil, GET이면 이전 값
 * KEEPTTL 없으면 기존 만료 시각 제거
 * 옵션 조합 검사 후 마지막 만료 값만 해석 (Redis와 동일), 같은 옵션 반복 허용
 */
public final class SetCommand implements Command {

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

        // 옵션 조합만 확인
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

        // 만료 값 해석
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
        RespValue previousValue = RespValue.NIL;
        if (previous != null && get) {
            // GET 없으면 자료형 무관 덮어쓰기
            if (!(previous.value() instanceof StringValue string)) {
                return Errors.wrongType();
            }
            previousValue = new RespValue.BulkString(string.bytes());
        }

        if ((nx && previous != null) || (xx && previous == null)) {
            return get ? previousValue : RespValue.NIL;
        }

        if (keepTtl && previous != null) {
            expireAt = previous.expireAtMillis();
        }
        // 지난 EXAT/PXAT도 저장 - 다음 조회 시 만료
        keyspace.put(key, new Entry(StringValue.of(args.get(1)), expireAt));
        return get ? previousValue : RespValue.OK;
    }
}
