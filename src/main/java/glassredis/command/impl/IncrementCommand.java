package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.command.Numbers;
import glassredis.resp.RespValue;
import glassredis.store.Entry;
import glassredis.store.Key;
import glassredis.store.Keyspace;
import glassredis.store.StringValue;

import java.util.List;
import java.util.OptionalLong;

/**
 * INCR key, DECR key, INCRBY key n, DECRBY key n
 * 키 없으면 0부터, 만료 시각 유지
 */
public final class IncrementCommand implements Command {

    private final String name;
    private final boolean decrement;
    private final boolean takesAmount;

    private IncrementCommand(String name, boolean decrement, boolean takesAmount) {
        this.name = name;
        this.decrement = decrement;
        this.takesAmount = takesAmount;
    }

    public static IncrementCommand incr() {
        return new IncrementCommand("INCR", false, false);
    }

    public static IncrementCommand decr() {
        return new IncrementCommand("DECR", true, false);
    }

    public static IncrementCommand incrBy() {
        return new IncrementCommand("INCRBY", false, true);
    }

    public static IncrementCommand decrBy() {
        return new IncrementCommand("DECRBY", true, true);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        if (args.size() != (takesAmount ? 2 : 1)) {
            return Errors.wrongNumberOfArguments(name);
        }

        long amount = 1;
        if (takesAmount) {
            OptionalLong parsed = Numbers.parseLong(args.get(1));
            if (parsed.isEmpty()) {
                return Errors.notAnInteger();
            }
            amount = parsed.getAsLong();
        }
        if (decrement) {
            // Long.MIN_VALUE - 부호 반전 불가
            if (amount == Long.MIN_VALUE) {
                return Errors.decrementOverflow();
            }
            amount = -amount;
        }

        Keyspace keyspace = ctx.keyspace();
        Key key = new Key(args.get(0));
        Entry entry = keyspace.get(key);

        long current = 0;
        if (entry != null) {
            if (!(entry.value() instanceof StringValue string)) {
                return Errors.wrongType();
            }
            OptionalLong parsed = Numbers.parseLong(string.bytes());
            if (parsed.isEmpty()) {
                return Errors.notAnInteger();
            }
            current = parsed.getAsLong();
        }

        long updated;
        try {
            updated = Math.addExact(current, amount);
        } catch (ArithmeticException overflow) {
            return Errors.incrementOverflow();
        }

        StringValue text = StringValue.ofLong(updated);
        keyspace.put(key, entry == null ? Entry.of(text) : entry.withValue(text));
        return new RespValue.Int(updated);
    }
}
