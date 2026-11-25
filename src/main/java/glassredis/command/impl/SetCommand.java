package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.command.Numbers;
import glassredis.command.Options;
import glassredis.resp.RespValue;
import glassredis.store.Entry;
import glassredis.store.Key;
import glassredis.store.Keyspace;
import glassredis.store.StringValue;

import java.util.ArrayList;
import java.util.List;
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
    public int arity() {
        return -3;
    }

    @Override
    public boolean writes() {
        return true;
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        // 옵션 조합만 확인
        boolean nx = false;
        boolean xx = false;
        boolean get = false;
        boolean keepTtl = false;
        ExpiryOption expiry = null;
        byte[] expiryArgument = null;

        for (int i = 2; i < args.size(); i++) {
            byte[] option = args.get(i);
            ExpiryOption kind = expiryOption(option);
            if (Options.is(option, "NX") && !xx) {
                nx = true;
            } else if (Options.is(option, "XX") && !nx) {
                xx = true;
            } else if (Options.is(option, "GET")) {
                get = true;
            } else if (Options.is(option, "KEEPTTL") && expiry == null) {
                keepTtl = true;
            } else if (kind != null && !keepTtl && (expiry == null || expiry == kind) && i + 1 < args.size()) {
                expiry = kind;
                expiryArgument = args.get(++i);
            } else {
                return Errors.syntaxError();
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
        RespValue previousValue = RespValue.NIL;
        if (get) {
            // getGenericCommand - lookupKeyRead, GET 없으면 자료형 무관 덮어쓰기
            Entry previous = keyspace.get(key);
            if (previous != null) {
                if (!(previous.value() instanceof StringValue string)) {
                    return Errors.wrongType();
                }
                previousValue = new RespValue.BulkString(string.bytes());
            }
        }

        boolean found = keyspace.getWrite(key) != null;
        if ((nx && found) || (xx && !found)) {
            return get ? previousValue : RespValue.NIL;
        }

        // setGenericCommand - 만료 옵션이 있으면 지우지 않고 뒤에서 덮어씀 (SETKEY_KEEPTTL)
        int flags = (keepTtl || expiry != null ? Keyspace.SETKEY_KEEPTTL : 0)
                | (found ? Keyspace.SETKEY_ALREADY_EXIST : Keyspace.SETKEY_DOESNT_EXIST);
        keyspace.setKey(key, StringValue.of(args.get(1)), flags);
        ctx.dirty();
        if (expiry != null) {
            // 지난 EXAT/PXAT도 저장 - 다음 조회 시 만료
            keyspace.setExpire(key, expireAt);
        }
        if (expiry != null && expiry != ExpiryOption.PXAT) {
            // 상대 시간은 재시작 후 밀리므로 절대 시각으로 - 다른 옵션은 버림
            ctx.propagateAs("SET", args.get(0), args.get(1), "PXAT", expireAt);
        } else if (get && expiry == null) {
            ctx.propagation().rewrite(withoutGet(ctx.propagation().argv()));
        }
        return get ? previousValue : RespValue.OK;
    }

    /** EX/PX/EXAT/PXAT 중 하나면 그 종류, 아니면 null */
    private static ExpiryOption expiryOption(byte[] option) {
        for (ExpiryOption kind : ExpiryOption.values()) {
            if (Options.is(option, kind.name())) {
                return kind;
            }
        }
        return null;
    }

    /** 값 뒤의 GET 전부 제거 - 이름·키·값 자리는 그대로, 비교는 옵션 해석과 같은 C 문자열 방식 */
    private static List<byte[]> withoutGet(List<byte[]> argv) {
        List<byte[]> kept = new ArrayList<>(argv.size());
        for (int i = 0; i < argv.size(); i++) {
            if (i >= 3 && Options.is(argv.get(i), "GET")) {
                continue;
            }
            kept.add(argv.get(i));
        }
        return kept;
    }
}
