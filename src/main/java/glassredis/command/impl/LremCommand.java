package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.command.Numbers;
import glassredis.resp.RespValue;
import glassredis.store.Entry;
import glassredis.store.Key;
import glassredis.store.ListValue;

import java.util.List;
import java.util.OptionalLong;

/**
 * LREM key count element - 삭제 개수
 * count 양수면 앞에서, 음수면 뒤에서 최대 |count|개, 0이면 전부
 */
public final class LremCommand implements Command {

    @Override
    public String name() {
        return "LREM";
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        if (args.size() != 3) {
            return Errors.wrongNumberOfArguments(name());
        }
        OptionalLong parsed = Numbers.parseLong(args.get(1));
        if (parsed.isEmpty()) {
            return Errors.notAnInteger();
        }
        long count = parsed.getAsLong();
        byte[] target = args.get(2);

        Key key = new Key(args.get(0));
        Entry entry = ctx.keyspace().get(key);
        if (entry == null) {
            return new RespValue.Int(0);
        }
        if (!(entry.value() instanceof ListValue list)) {
            return Errors.wrongType();
        }

        // Long.MIN_VALUE - 부호 반전 불가라 제한 없음 취급, 결과 동일
        long limit = count == 0 || count == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(count);
        long removed = list.remove(target, limit, count < 0);

        if (list.length() == 0) {
            ctx.keyspace().remove(key);
        } else if (removed > 0) {
            list.afterShrink();
        }
        return new RespValue.Int(removed);
    }
}
