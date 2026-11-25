package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.command.Lookup;
import glassredis.command.IndexRange;
import glassredis.command.Numbers;
import glassredis.resp.RespValue;
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
    public int arity() {
        return 4;
    }

    @Override
    public boolean writes() {
        return true;
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        OptionalLong start = Numbers.parseLong(args.get(1));
        OptionalLong stop = Numbers.parseLong(args.get(2));
        if (start.isEmpty() || stop.isEmpty()) {
            return Errors.notAnInteger();
        }

        Key key = new Key(args.get(0));
        Lookup<ListValue> found = Lookup.listWrite(ctx, key, RespValue.OK);
        if (found.reply() != null) {
            return found.reply();
        }
        ListValue list = found.value();

        IndexRange range = IndexRange.of(start.getAsLong(), stop.getAsLong(), list.length());
        if (range == null) {
            ctx.dirty(list.length());
            ctx.keyspace().delete(key);
            return RespValue.OK;
        }
        long before = list.length();
        list.trim(range.start(), list.length() - 1 - range.end());
        ctx.dirty(before - list.length());
        list.afterShrink();
        return RespValue.OK;
    }
}
