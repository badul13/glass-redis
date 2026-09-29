package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.resp.RespValue;
import glassredis.store.Entry;
import glassredis.store.Key;
import glassredis.store.SortedSetValue;

import java.util.List;

/** ZRANK/ZREVRANK key member - 0부터 센 순위, 멤버 없으면 nil */
public final class ZrankCommand implements Command {

    private final String name;
    private final boolean reverse;

    private ZrankCommand(String name, boolean reverse) {
        this.name = name;
        this.reverse = reverse;
    }

    public static ZrankCommand ascending() {
        return new ZrankCommand("ZRANK", false);
    }

    public static ZrankCommand descending() {
        return new ZrankCommand("ZREVRANK", true);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        if (args.size() != 2) {
            return Errors.wrongNumberOfArguments(name);
        }
        Entry entry = ctx.keyspace().get(new Key(args.get(0)));
        if (entry == null) {
            return RespValue.NIL;
        }
        if (!(entry.value() instanceof SortedSetValue zset)) {
            return Errors.wrongType();
        }
        long rank = zset.rank(new Key(args.get(1)));
        if (rank < 0) {
            return RespValue.NIL;
        }
        return new RespValue.Int(reverse ? zset.size() - 1 - rank : rank);
    }
}
