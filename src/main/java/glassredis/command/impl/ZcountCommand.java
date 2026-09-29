package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.command.ScoreRanges;
import glassredis.resp.RespValue;
import glassredis.store.Entry;
import glassredis.store.Key;
import glassredis.store.ScoreRange;
import glassredis.store.SkipList;
import glassredis.store.SortedSetValue;

import java.util.List;

/**
 * {@code ZCOUNT key min max} — 점수가 구간에 드는 멤버 수. 구간 표기는 {@link ScoreRanges}.
 *
 * <p>구간의 첫 노드와 마지막 노드의 순위 차로 센다. 하나씩 세지 않으므로 구간에 백만 개가 들어 있어도 O(log n) 이다.
 */
public final class ZcountCommand implements Command {

    @Override
    public String name() {
        return "ZCOUNT";
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        if (args.size() != 3) {
            return Errors.wrongNumberOfArguments(name());
        }
        ScoreRange range = ScoreRanges.parse(args.get(1), args.get(2));
        if (range == null) {
            return Errors.minOrMaxNotAFloat();
        }

        Entry entry = ctx.keyspace().get(new Key(args.get(0)));
        if (entry == null) {
            return new RespValue.Int(0);
        }
        if (!(entry.value() instanceof SortedSetValue zset)) {
            return Errors.wrongType();
        }

        SkipList order = zset.order();
        SkipList.Node first = order.firstInRange(range);
        if (first == null) {
            return new RespValue.Int(0);
        }
        SkipList.Node last = order.lastInRange(range);
        long count = order.rank(last.score(), last.member()) - order.rank(first.score(), first.member()) + 1;
        return new RespValue.Int(count);
    }
}
