package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.command.Numbers;
import glassredis.resp.RespValue;
import glassredis.store.Entry;
import glassredis.store.Key;
import glassredis.store.ListValue;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.OptionalLong;

/**
 * {@code LREM key count element} — {@code element} 와 같은 원소를 지우고, 지운 개수를 준다.
 *
 * <ul>
 *   <li>{@code count > 0} — 앞에서부터 최대 count 개</li>
 *   <li>{@code count < 0} — 뒤에서부터 최대 |count| 개</li>
 *   <li>{@code count = 0} — 전부</li>
 * </ul>
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

        // Long.MIN_VALUE 는 부호를 뒤집을 수 없지만, 그만큼 지울 원소도 없으니 "제한 없음"으로 봐도 같다.
        long limit = count == 0 || count == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(count);
        Iterator<byte[]> it = count < 0 ? list.elements().descendingIterator() : list.elements().iterator();
        long removed = 0;
        while (removed < limit && it.hasNext()) {
            if (Arrays.equals(it.next(), target)) {
                it.remove();
                removed++;
            }
        }

        if (list.size() == 0) {
            ctx.keyspace().remove(key);
        }
        return new RespValue.Int(removed);
    }
}
