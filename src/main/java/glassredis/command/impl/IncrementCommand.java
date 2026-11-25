package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.command.Lookup;
import glassredis.command.Numbers;
import glassredis.resp.RespValue;
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
    public int arity() {
        return takesAmount ? 3 : 2;
    }

    @Override
    public boolean writes() {
        return true;
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
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
        Lookup<StringValue> found = Lookup.stringWrite(ctx, key, null);
        if (found.reply() != null) {
            return found.reply();
        }

        long current = 0;
        if (found.value() != null) {
            OptionalLong parsed = Numbers.parseLong(found.value().bytes());
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

        // incrDecrCommand - 공유 아닌 int 값이 공유 범위(0~9999) 밖으로 바뀌면 같은 객체 제자리 수정 (dict 재조회 없음)
        // 그 밖은 createStringObjectFromLongLongForValue 새 값으로 dbReplaceValue 또는 dbAdd
        StringValue text = StringValue.ofLong(updated);
        StringValue old = found.value();
        if (old != null && old.form() == StringValue.Form.INT && old.sharedIntegerIndex() < 0
                && (updated < 0 || updated >= StringValue.SHARED_INTEGERS)) {
            keyspace.replaceLookedUpValue(key, text);
        } else if (old != null) {
            keyspace.replaceValue(key, text);
        } else {
            keyspace.add(key, text);
        }
        ctx.dirty();
        return new RespValue.Int(updated);
    }
}
