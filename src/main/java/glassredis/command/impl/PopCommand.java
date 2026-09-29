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
 * LPOP/RPOP key [count]
 * count 지정 시 1이어도 배열 응답, 비면 키 삭제
 * 없는 키 + count - 널 배열 대신 널 벌크 문자열 (Redis와 차이)
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
