package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.command.IndexRange;
import glassredis.command.Numbers;
import glassredis.resp.RespValue;
import glassredis.store.Entry;
import glassredis.store.Key;
import glassredis.store.ListValue;

import java.util.List;
import java.util.OptionalLong;

/** LTRIM key start stop - 인덱스 규칙은 {@link IndexRange}, 빈 구간이면 키 삭제 */
public final class LtrimCommand implements Command {

    @Override
    public String name() {
        return "LTRIM";
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        if (args.size() != 3) {
            return Errors.wrongNumberOfArguments(name());
        }
        OptionalLong start = Numbers.parseLong(args.get(1));
        OptionalLong stop = Numbers.parseLong(args.get(2));
        if (start.isEmpty() || stop.isEmpty()) {
            return Errors.notAnInteger();
        }

        Key key = new Key(args.get(0));
        Entry entry = ctx.keyspace().get(key);
        if (entry == null) {
            return RespValue.OK;
        }
        if (!(entry.value() instanceof ListValue list)) {
            return Errors.wrongType();
        }

        IndexRange range = IndexRange.of(start.getAsLong(), stop.getAsLong(), list.size());
        if (range == null) {
            ctx.keyspace().remove(key);
            return RespValue.OK;
        }
        list.trim(range.start(), list.length() - 1 - range.end());
        list.afterShrink();
        return RespValue.OK;
    }
}
