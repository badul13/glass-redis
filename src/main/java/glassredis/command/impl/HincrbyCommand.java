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
 * HINCRBY key field increment - 키나 필드 없으면 0부터
 * 필드 값이 정수 아닐 때 에러 - INCRBY와 다른 hash value is not an integer
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
        // 실패 시 빈 Hash 잔존 방지 - 검사 후 삽입
        if (entry == null) {
            keyspace.put(key, Entry.of(hash));
        }
        return new RespValue.Int(updated);
    }
}
