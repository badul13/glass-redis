package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Lookup;
import glassredis.resp.RespValue;
import glassredis.store.HashValue;
import glassredis.store.Key;

import java.util.ArrayList;
import java.util.List;

/**
 * HGET key field, HMGET key field [field ...] - 없는 필드는 nil
 * HMGET - MGET과 달리 Hash 아닌 키에 WRONGTYPE
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
    public int arity() {
        return multiple ? -3 : 3;
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        // 없는 키는 빈 Hash 취급 - 필드마다 nil
        Lookup<HashValue> found = Lookup.hash(ctx, new Key(args.get(0)), null);
        if (found.reply() != null) {
            return found.reply();
        }
        HashValue hash = found.value();

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
