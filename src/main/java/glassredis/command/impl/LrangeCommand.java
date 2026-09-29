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

import java.util.Iterator;
import java.util.List;
import java.util.OptionalLong;

/**
 * {@code LRANGE key start stop} — 구간의 원소들. 인덱스 규칙은 {@link IndexRange}.
 *
 * <p>인덱스로 바로 갈 수 없어서 구간까지 걸어가야 한다. 비용은 "건너뛴 수 + 돌려준 수"에 비례하므로
 * 양 끝 근처는 싸고, 긴 List 의 한가운데는 비싸다.
 */
public final class LrangeCommand implements Command {

    @Override
    public String name() {
        return "LRANGE";
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

        Entry entry = ctx.keyspace().get(new Key(args.get(0)));
        if (entry == null) {
            return RespValue.EMPTY_ARRAY;
        }
        if (!(entry.value() instanceof ListValue list)) {
            return Errors.wrongType();
        }

        IndexRange range = IndexRange.of(start.getAsLong(), stop.getAsLong(), list.size());
        if (range == null) {
            return RespValue.EMPTY_ARRAY;
        }
        // 가까운 쪽 끝에서 출발한다. "최근 10개"({@code LRANGE k -10 -1})는 List 가 아무리 길어도 10 걸음이다.
        int size = list.size();
        boolean fromTail = size - 1 - range.end() < range.start();
        RespValue[] items = new RespValue[range.count()];
        if (fromTail) {
            Iterator<byte[]> it = list.elements().descendingIterator();
            for (int i = size - 1; i >= range.start(); i--) {
                byte[] element = it.next();
                if (i <= range.end()) {
                    items[i - range.start()] = new RespValue.BulkString(element);
                }
            }
        } else {
            Iterator<byte[]> it = list.elements().iterator();
            for (int i = 0; i <= range.end(); i++) {
                byte[] element = it.next();
                if (i >= range.start()) {
                    items[i - range.start()] = new RespValue.BulkString(element);
                }
            }
        }
        return new RespValue.Array(List.of(items));
    }
}
