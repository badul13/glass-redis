package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.command.Numbers;
import glassredis.resp.RespValue;
import glassredis.store.Entry;
import glassredis.store.Key;
import glassredis.store.Keyspace;
import glassredis.store.SortedSetValue;

import java.util.List;
import java.util.OptionalDouble;

/**
 * {@code ZINCRBY key increment member} — 점수에 더하고, 결과 점수를 준다. 없는 멤버는 0 에서 시작한다.
 *
 * <p>점수가 바뀌면 스킵 리스트 안에서 자리를 옮긴다. 순위표에서 점수가 오른 사람이 위로 올라가는 장면이다.
 */
public final class ZincrbyCommand implements Command {

    @Override
    public String name() {
        return "ZINCRBY";
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        if (args.size() != 3) {
            return Errors.wrongNumberOfArguments(name());
        }
        OptionalDouble increment = Numbers.parseDouble(args.get(1));
        if (increment.isEmpty()) {
            return Errors.notAFloat();
        }

        Keyspace keyspace = ctx.keyspace();
        Key key = new Key(args.get(0));
        Entry entry = keyspace.get(key);
        SortedSetValue zset;
        if (entry == null) {
            zset = SortedSetValue.create(1, args.get(2).length);
        } else if (entry.value() instanceof SortedSetValue existing) {
            zset = existing;
        } else {
            return Errors.wrongType();
        }

        Key member = new Key(args.get(2));
        Double current = zset.score(member);
        double score = (current == null ? 0 : current) + increment.getAsDouble();
        if (Double.isNaN(score)) {
            return Errors.scoreIsNaN();
        }
        zset.put(member, score);
        if (entry == null) {
            keyspace.put(key, Entry.of(zset));
        }
        return new RespValue.BulkString(Numbers.formatDouble(score));
    }
}
