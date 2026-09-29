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

/** SADD key member [member ...] - 새로 추가된 수 */
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
            // 첫 원소와 삽입 개수로 초기 인코딩 선택
            set = SetValue.create(args.get(1), args.size() - 1);
            keyspace.put(key, Entry.of(set));
        } else if (entry.value() instanceof SetValue existing) {
            set = existing;
            set.prepareForAdd(args.size() - 1);
        } else {
            return Errors.wrongType();
        }

        long added = 0;
        for (int i = 1; i < args.size(); i++) {
            if (set.add(args.get(i))) {
                added++;
            }
        }
        return new RespValue.Int(added);
    }
}
