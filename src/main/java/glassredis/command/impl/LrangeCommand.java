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

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

/**
 * LRANGE key start stop - 인덱스 규칙은 {@link IndexRange}
 * 비용 - 건너뛴 수 + 반환 수에 비례
 * 가까운 쪽 끝에서 탐색, quicklist는 노드 단위 건너뛰기
 */
public final class LrangeCommand implements Command {

    @Override
    public String name() {
        return "LRANGE";
    }

    @Override
    public int arity() {
        return 4;
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        OptionalLong start = Numbers.parseLong(args.get(1));
        OptionalLong stop = Numbers.parseLong(args.get(2));
        if (start.isEmpty() || stop.isEmpty()) {
            return Errors.notAnInteger();
        }

        Lookup<ListValue> found = Lookup.list(ctx, new Key(args.get(0)), RespValue.EMPTY_ARRAY);
        if (found.reply() != null) {
            return found.reply();
        }
        ListValue list = found.value();

        IndexRange range = IndexRange.of(start.getAsLong(), stop.getAsLong(), list.length());
        if (range == null) {
            return RespValue.EMPTY_ARRAY;
        }
        List<RespValue> items = new ArrayList<>(range.count());
        for (byte[] element : list.range(range.start(), range.end())) {
            items.add(new RespValue.BulkString(element));
        }
        return new RespValue.Array(items);
    }
}
