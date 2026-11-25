package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Lookup;
import glassredis.resp.RespValue;
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
    public int arity() {
        return -3;
    }

    @Override
    public boolean writes() {
        return true;
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        Keyspace keyspace = ctx.keyspace();
        Key key = new Key(args.get(0));
        Lookup<SetValue> found = Lookup.setWrite(ctx, key, null);
        if (found.reply() != null) {
            return found.reply();
        }
        SetValue set;
        if (found.value() == null) {
            // 첫 원소와 삽입 개수로 초기 인코딩 선택
            set = SetValue.create(args.get(1), args.size() - 1);
            keyspace.add(key, set);
        } else {
            set = found.value();
            set.prepareForAdd(args.size() - 1);
        }

        long added = 0;
        for (int i = 1; i < args.size(); i++) {
            if (set.add(args.get(i))) {
                added++;
            }
        }
        ctx.dirty(added);
        return new RespValue.Int(added);
    }
}
