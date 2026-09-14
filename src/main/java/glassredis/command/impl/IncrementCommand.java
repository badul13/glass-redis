package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.command.Numbers;
import glassredis.resp.RespValue;
import glassredis.store.Entry;
import glassredis.store.Key;
import glassredis.store.Keyspace;

import java.util.List;
import java.util.OptionalLong;

/**
 * {@code INCR key} / {@code DECR key} / {@code INCRBY key n} / {@code DECRBY key n}
 *
 * <p>값을 64비트 정수로 해석해 더하고, 결과를 다시 문자열로 저장한다. Redis 에 정수 타입이 따로 있는 게 아니라
 * 정수처럼 생긴 문자열이 있을 뿐이다. 키가 없으면 0 에서 시작한다.
 *
 * <p>"읽고, 더하고, 쓰는" 세 단계라서 여러 스레드가 동시에 실행하면 서로의 결과를 덮어써 증가분이 사라진다.
 * 여기서는 실행 스레드가 하나뿐이라 락 없이도 그런 일이 생기지 않는다.
 *
 * <p>값을 고치는 명령이므로 만료 시각은 그대로 둔다.
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
            // long 의 최솟값은 부호를 뒤집을 수 없다. -(-2^63) 은 2^63 인데 long 의 최댓값은 2^63-1 이다.
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
            OptionalLong parsed = Numbers.parseLong(entry.value());
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

        byte[] text = Numbers.toBytes(updated);
        keyspace.put(key, entry == null ? Entry.of(text) : entry.withValue(text));
        return new RespValue.Int(updated);
    }
}
