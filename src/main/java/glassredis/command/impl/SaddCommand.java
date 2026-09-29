package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.resp.RespValue;
import glassredis.store.Entry;
import glassredis.store.Key;
import glassredis.store.Keyspace;
import glassredis.store.SetValue;

import java.util.List;

/**
 * {@code SADD key member [member ...]} — 원소를 넣고, 새로 들어간 수를 준다.
 * 이미 있던 원소는 세지 않는다. 키가 없으면 새 Set 을 만든다.
 */
public final class SaddCommand implements Command {

    @Override
    public String name() {
        return "SADD";
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        if (args.size() < 2) {
            return Errors.wrongNumberOfArguments(name());
        }
        Keyspace keyspace = ctx.keyspace();
        Key key = new Key(args.get(0));
        Entry entry = keyspace.get(key);

        SetValue set;
        if (entry == null) {
            set = new SetValue();
            keyspace.put(key, Entry.of(set));
        } else if (entry.value() instanceof SetValue existing) {
            set = existing;
        } else {
            return Errors.wrongType();
        }

        long added = 0;
        for (int i = 1; i < args.size(); i++) {
            if (set.members().add(new Key(args.get(i)))) {
                added++;
            }
        }
        return new RespValue.Int(added);
    }
}
