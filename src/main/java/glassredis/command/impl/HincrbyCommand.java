package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.command.Numbers;
import glassredis.resp.RespValue;
import glassredis.store.Entry;
import glassredis.store.HashValue;
import glassredis.store.Key;
import glassredis.store.Keyspace;

import java.util.List;
import java.util.OptionalLong;

/**
 * {@code HINCRBY key field increment} — 필드 값을 정수로 보고 더한다. {@code INCRBY} 의 Hash 판이다.
 * 키나 필드가 없으면 0 에서 시작한다.
 *
 * <p>에러 문구가 {@code INCRBY} 와 조금 다르다. 필드 값이 정수가 아니면 {@code hash value is not an integer} 다.
 * 증가량이 정수가 아닐 때는 {@code INCRBY} 와 같은 문구다.
 */
public final class HincrbyCommand implements Command {

    @Override
    public String name() {
        return "HINCRBY";
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        if (args.size() != 3) {
            return Errors.wrongNumberOfArguments(name());
        }
        OptionalLong amount = Numbers.parseLong(args.get(2));
        if (amount.isEmpty()) {
            return Errors.notAnInteger();
        }

        Keyspace keyspace = ctx.keyspace();
        Key key = new Key(args.get(0));
        Entry entry = keyspace.get(key);
        HashValue hash;
        if (entry == null) {
            hash = new HashValue();
        } else if (entry.value() instanceof HashValue existing) {
            hash = existing;
        } else {
            return Errors.wrongType();
        }

        Key field = new Key(args.get(1));
        long current = 0;
        byte[] stored = hash.get(field);
        if (stored != null) {
            OptionalLong parsed = Numbers.parseLong(stored);
            if (parsed.isEmpty()) {
                return Errors.hashValueNotAnInteger();
            }
            current = parsed.getAsLong();
        }

        long updated;
        try {
            updated = Math.addExact(current, amount.getAsLong());
        } catch (ArithmeticException overflow) {
            return Errors.incrementOverflow();
        }

        hash.set(field, Numbers.toBytes(updated));
        // 에러로 끝날 수 있는 검사를 다 지난 뒤에야 새 키를 넣는다. 먼저 넣으면 실패했을 때 빈 Hash 가 남는다.
        if (entry == null) {
            keyspace.put(key, Entry.of(hash));
        }
        return new RespValue.Int(updated);
    }
}
