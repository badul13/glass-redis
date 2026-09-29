package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.resp.RespValue;
import glassredis.store.Entry;
import glassredis.store.HashValue;
import glassredis.store.Key;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code HGET key field} / {@code HMGET key field [field ...]} — 필드의 값. 없는 필드는 nil.
 *
 * <p>{@code HMGET} 은 {@code MGET} 처럼 응답 순서가 인자 순서와 같다. 키가 없어도 에러가 아니라
 * 필드 수만큼 nil 이 든 배열이다. 다만 {@code MGET} 과 달리 키가 Hash 가 아니면 WRONGTYPE 이다.
 */
public final class HgetCommand implements Command {

    private final String name;
    private final boolean multiple;

    private HgetCommand(String name, boolean multiple) {
        this.name = name;
        this.multiple = multiple;
    }

    public static HgetCommand single() {
        return new HgetCommand("HGET", false);
    }

    public static HgetCommand multiple() {
        return new HgetCommand("HMGET", true);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        if (multiple ? args.size() < 2 : args.size() != 2) {
            return Errors.wrongNumberOfArguments(name);
        }
        Entry entry = ctx.keyspace().get(new Key(args.get(0)));
        HashValue hash = null;
        if (entry != null) {
            if (!(entry.value() instanceof HashValue existing)) {
                return Errors.wrongType();
            }
            hash = existing;
        }

        if (!multiple) {
            return lookup(hash, args.get(1));
        }
        List<RespValue> values = new ArrayList<>(args.size() - 1);
        for (int i = 1; i < args.size(); i++) {
            values.add(lookup(hash, args.get(i)));
        }
        return new RespValue.Array(values);
    }

    private static RespValue lookup(HashValue hash, byte[] field) {
        byte[] value = hash == null ? null : hash.get(new Key(field));
        return value == null ? RespValue.NIL : new RespValue.BulkString(value);
    }
}
