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

/**
 * {@code LTRIM key start stop} — 구간만 남기고 나머지를 잘라낸다. 인덱스 규칙은 {@link IndexRange}.
 *
 * <p>{@code LPUSH} 뒤에 {@code LTRIM k 0 99} 를 붙이면 "최근 100개만 남기는 목록"이 된다.
 * 이 명령의 대표적인 쓰임새다. 남길 구간이 비면 키째로 지운다.
 */
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
