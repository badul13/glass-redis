package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.resp.RespValue;
import glassredis.store.Entry;
import glassredis.store.Key;
import glassredis.store.Keyspace;
import glassredis.store.ListValue;

import java.util.List;

/**
 * LPUSH/RPUSH key element [element ...] - 삽입 후 길이
 * 하나씩 차례로 삽입 - LPUSH k a b c 결과는 [c, b, a]
 */
public final class PushCommand implements Command {

    private final String name;
    private final boolean left;

    private PushCommand(String name, boolean left) {
        this.name = name;
        this.left = left;
    }

    public static PushCommand left() {
        return new PushCommand("LPUSH", true);
    }

    public static PushCommand right() {
        return new PushCommand("RPUSH", false);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        if (args.size() < 2) {
            return Errors.wrongNumberOfArguments(name);
        }
        Keyspace keyspace = ctx.keyspace();
        Key key = new Key(args.get(0));
        Entry entry = keyspace.get(key);

        ListValue list;
        if (entry == null) {
            list = new ListValue();
            keyspace.put(key, Entry.of(list));
        } else if (entry.value() instanceof ListValue existing) {
            list = existing;
        } else {
            return Errors.wrongType();
        }

        List<byte[]> values = args.subList(1, args.size());
        // 전부 넣었을 때 8KB 초과 예상 시 quicklist로 선전환
        list.prepareForAppend(values);
        for (byte[] value : values) {
            list.push(value, left);
        }
        return new RespValue.Int(list.length());
    }
}
