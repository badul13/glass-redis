package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.command.Numbers;
import glassredis.resp.RespValue;
import glassredis.store.Entry;
import glassredis.store.Key;
import glassredis.store.ListValue;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

/**
 * {@code LPOP key [count]} / {@code RPOP key [count]} — List 의 앞 또는 뒤에서 꺼낸다.
 *
 * <p>{@code count} 를 주느냐에 따라 응답 모양이 다르다. 안 주면 원소 하나(벌크 문자열),
 * 주면 배열이다. {@code count} 가 1 이어도 배열이다.
 *
 * <p>마지막 원소를 꺼내면 키째로 사라진다. Redis 에는 "빈 List" 라는 상태가 없다 —
 * 원소가 0 개가 되는 순간 키가 지워지고, 다음 {@code PUSH} 가 새로 만든다.
 *
 * <p>없는 키에 {@code count} 를 주면 실제 Redis 는 널 배열({@code *-1})을 준다.
 * 여기에는 널 배열 타입이 없어 널 벌크 문자열을 준다. redis-cli 에서는 둘 다 {@code (nil)} 로 보인다.
 */
public final class PopCommand implements Command {

    private final String name;
    private final boolean left;

    private PopCommand(String name, boolean left) {
        this.name = name;
        this.left = left;
    }

    public static PopCommand left() {
        return new PopCommand("LPOP", true);
    }

    public static PopCommand right() {
        return new PopCommand("RPOP", false);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        if (args.isEmpty() || args.size() > 2) {
            return Errors.wrongNumberOfArguments(name);
        }
        boolean hasCount = args.size() == 2;
        long count = 1;
        if (hasCount) {
            OptionalLong parsed = Numbers.parseLong(args.get(1));
            if (parsed.isEmpty() || parsed.getAsLong() < 0) {
                return Errors.mustBePositive();
            }
            count = parsed.getAsLong();
        }

        Key key = new Key(args.get(0));
        Entry entry = ctx.keyspace().get(key);
        if (entry == null) {
            return RespValue.NIL;
        }
        if (!(entry.value() instanceof ListValue list)) {
            return Errors.wrongType();
        }

        RespValue reply;
        if (hasCount) {
            List<RespValue> popped = new ArrayList<>();
            for (long i = 0; i < count && list.length() > 0; i++) {
                popped.add(new RespValue.BulkString(list.pop(left)));
            }
            reply = new RespValue.Array(popped);
        } else {
            reply = new RespValue.BulkString(list.pop(left));
        }

        if (list.length() == 0) {
            ctx.keyspace().remove(key);
        } else {
            list.afterShrink();
        }
        return reply;
    }
}
