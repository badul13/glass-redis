package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.command.Lookup;
import glassredis.command.ScoreRanges;
import glassredis.resp.RespValue;
import glassredis.store.Key;
import glassredis.store.ScoreRange;
import glassredis.store.SortedSetValue;

import java.util.List;

/** ZCOUNT key min max - 구간 표기는 {@link ScoreRanges}, 양 끝 순위 차로 계산해 skiplist에서 O(log n) */
public final class ZcountCommand implements Command {

    @Override
    public String name() {
        return "ZCOUNT";
    }

    @Override
    public int arity() {
        return 4;
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        ScoreRange range = ScoreRanges.parse(args.get(1), args.get(2));
        if (range == null) {
            return Errors.minOrMaxNotAFloat();
        }

        Lookup<SortedSetValue> found = Lookup.zset(ctx, new Key(args.get(0)), new RespValue.Int(0));
        if (found.reply() != null) {
            return found.reply();
        }
        SortedSetValue zset = found.value();

        long first = zset.firstRankIn(range);
        long last = zset.lastRankIn(range);
        return new RespValue.Int(first == -1 || last == -1 || first > last ? 0 : last - first + 1);
    }
}
