package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.command.Numbers;
import glassredis.resp.RespValue;
import glassredis.store.Entry;
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
    public RespValue execute(Context ctx, List<byte[]> args) {
        if (args.size() != 2) {
            return Errors.wrongNumberOfArguments(name());
        }
        Entry entry = ctx.keyspace().get(new Key(args.get(0)));
        if (entry == null) {
            return RespValue.NIL;
        }
        if (!(entry.value() instanceof SortedSetValue zset)) {
            return Errors.wrongType();
        }
        Double score = zset.score(new Key(args.get(1)));
        return score == null ? RespValue.NIL : new RespValue.BulkString(Numbers.formatDouble(score));
    }
}
