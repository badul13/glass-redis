package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.command.Numbers;
import glassredis.resp.RespValue;
import glassredis.store.Entry;
import glassredis.store.Key;
import glassredis.store.ListValue;

import java.util.Iterator;
import java.util.List;
import java.util.OptionalLong;

/**
 * {@code LINDEX key index} — 위치 하나의 원소. 음수는 뒤에서부터 센다. 범위 밖이면 nil.
 *
 * <p>인덱스로 바로 갈 수 없어서 걸어가야 한다. 가까운 쪽 끝에서 출발하므로
 * {@code LINDEX k -1} 은 List 가 아무리 길어도 한 걸음이다.
 */
public final class LindexCommand implements Command {

    @Override
    public String name() {
        return "LINDEX";
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        if (args.size() != 2) {
            return Errors.wrongNumberOfArguments(name());
        }
        OptionalLong parsed = Numbers.parseLong(args.get(1));
        if (parsed.isEmpty()) {
            return Errors.notAnInteger();
        }

        Entry entry = ctx.keyspace().get(new Key(args.get(0)));
        if (entry == null) {
            return RespValue.NIL;
        }
        if (!(entry.value() instanceof ListValue list)) {
            return Errors.wrongType();
        }

        int size = list.size();
        long index = parsed.getAsLong() < 0 ? parsed.getAsLong() + size : parsed.getAsLong();
        if (index < 0 || index >= size) {
            return RespValue.NIL;
        }

        boolean fromTail = index >= size / 2;
        Iterator<byte[]> it = fromTail ? list.elements().descendingIterator() : list.elements().iterator();
        long steps = fromTail ? size - 1 - index : index;
        for (long i = 0; i < steps; i++) {
            it.next();
        }
        return new RespValue.BulkString(it.next());
    }
}
