package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.command.Lookup;
import glassredis.command.Numbers;
import glassredis.resp.RespValue;
import glassredis.store.Key;
import glassredis.store.ListValue;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

/**
 * LPOP/RPOP key [count]
 * count 지정 시 1이어도 배열 응답, 비면 키 삭제
 * 없는 키 - count 없으면 널 벌크 문자열, 있으면 널 배열 (*-1, count 0 도 같음)
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
    public int arity() {
        return -2;
    }

    @Override
    public boolean writes() {
        return true;
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        // 최대 개수 - arity -2 로 표현 불가
        if (args.size() > 2) {
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
        Lookup<ListValue> found = Lookup.listWrite(ctx, key, hasCount ? RespValue.NIL_ARRAY : RespValue.NIL);
        if (found.reply() != null) {
            return found.reply();
        }
        ListValue list = found.value();
        if (hasCount && count == 0) {
            // 빠른 종료 - 변경·인코딩 확인 없음
            return RespValue.EMPTY_ARRAY;
        }

        RespValue reply;
        if (hasCount) {
            List<RespValue> popped = new ArrayList<>();
            for (long i = 0; i < count && list.length() > 0; i++) {
                popped.add(new RespValue.BulkString(list.pop(left)));
            }
            reply = new RespValue.Array(popped);
            ctx.dirty(popped.size());
        } else {
            reply = new RespValue.BulkString(list.pop(left));
            ctx.dirty();
        }

        if (list.length() == 0) {
            ctx.keyspace().delete(key);
        } else {
            list.afterShrink();
        }
        return reply;
    }
}
