package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Lookup;
import glassredis.command.Numbers;
import glassredis.resp.RespValue;
import glassredis.store.Key;
import glassredis.store.SortedSetValue;

import java.util.List;

/** ZSCORE key member - 점수는 벌크 문자열, 없으면 nil */
public final class ZscoreCommand implements Command {

    @Override
    public String name() {
        return "ZSCORE";
    }

    @Override
    public int arity() {
        return 3;
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        Lookup<SortedSetValue> found = Lookup.zset(ctx, new Key(args.get(0)), RespValue.NIL);
        if (found.reply() != null) {
            return found.reply();
        }
        SortedSetValue zset = found.value();
        Double score = zset.score(new Key(args.get(1)));
        return score == null ? RespValue.NIL : new RespValue.BulkString(Numbers.formatDouble(score));
    }
}
