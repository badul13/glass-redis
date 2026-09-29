package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.command.ScoreRanges;
import glassredis.resp.RespValue;
import glassredis.store.Entry;
import glassredis.store.Key;
import glassredis.store.ScoreRange;
import glassredis.store.SortedSetValue;

import java.util.List;

/**
 * {@code ZCOUNT key min max} — 점수가 구간에 드는 멤버 수. 구간 표기는 {@link ScoreRanges}.
 *
 * <p>구간의 첫 멤버와 마지막 멤버의 순위 차로 센다. skiplist 면 하나씩 세지 않으므로 구간에 백만 개가 들어 있어도
 * O(log n) 이다. listpack(128개 이하)이면 훑는다.
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

        long first = zset.firstRankIn(range);
        long last = zset.lastRankIn(range);
        return new RespValue.Int(first == -1 || last == -1 || first > last ? 0 : last - first + 1);
    }
}
