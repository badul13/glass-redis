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
 * {@code LPUSH key element [element ...]} / {@code RPUSH key element [element ...]}
 * — List 의 앞(왼쪽) 또는 뒤(오른쪽)에 넣고, 넣은 뒤의 길이를 준다. 키가 없으면 새 List 를 만든다.
 *
 * <p>원소를 여러 개 주면 적은 순서대로 하나씩 넣는다. 그래서 {@code LPUSH k a b c} 의 결과는
 * {@code [c, b, a]} 다. 한 덩어리로 앞에 붙는 게 아니다.
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

        for (int i = 1; i < args.size(); i++) {
            if (left) {
                list.elements().addFirst(args.get(i));
            } else {
                list.elements().addLast(args.get(i));
            }
        }
        return new RespValue.Int(list.size());
    }
}
