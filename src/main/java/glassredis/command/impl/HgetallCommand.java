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
 * HGETALL key, HKEYS key, HVALS key - 키 없으면 빈 배열
 * HGETALL - 필드와 값을 번갈아 펼친 배열
 * 순서 - listpack은 삽입 순, hashtable은 해시 씨앗 따라 기동마다 상이
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
        hash.forEach((field, value) -> {
            if (withFields) {
                items.add(new RespValue.BulkString(field));
            }
            if (withValues) {
                items.add(new RespValue.BulkString(value));
            }
        });
        return new RespValue.Array(items);
    }
}
