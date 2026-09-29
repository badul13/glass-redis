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
import java.util.Map;

/**
 * {@code HGETALL key} / {@code HKEYS key} / {@code HVALS key} — Hash 전체를 훑는다. 키가 없으면 빈 배열.
 *
 * <p>{@code HGETALL} 은 맵이 아니라 {@code [필드1, 값1, 필드2, 값2, ...]} 로 펼친 배열이다.
 * RESP2 에는 맵 타입이 없어서다. 짝을 맞춰 읽는 건 클라이언트 몫이다.
 */
public final class HgetallCommand implements Command {

    private final String name;
    private final boolean withFields;
    private final boolean withValues;

    private HgetallCommand(String name, boolean withFields, boolean withValues) {
        this.name = name;
        this.withFields = withFields;
        this.withValues = withValues;
    }

    public static HgetallCommand all() {
        return new HgetallCommand("HGETALL", true, true);
    }

    public static HgetallCommand fields() {
        return new HgetallCommand("HKEYS", true, false);
    }

    public static HgetallCommand values() {
        return new HgetallCommand("HVALS", false, true);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        if (args.size() != 1) {
            return Errors.wrongNumberOfArguments(name);
        }
        Entry entry = ctx.keyspace().get(new Key(args.get(0)));
        if (entry == null) {
            return RespValue.EMPTY_ARRAY;
        }
        if (!(entry.value() instanceof HashValue hash)) {
            return Errors.wrongType();
        }

        List<RespValue> items = new ArrayList<>(hash.size() * (withFields && withValues ? 2 : 1));
        for (Map.Entry<Key, byte[]> field : hash.fields().entrySet()) {
            if (withFields) {
                items.add(new RespValue.BulkString(field.getKey().bytes()));
            }
            if (withValues) {
                items.add(new RespValue.BulkString(field.getValue()));
            }
        }
        return new RespValue.Array(items);
    }
}
