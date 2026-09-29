package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.resp.RespValue;
import glassredis.store.Entry;
import glassredis.store.HashValue;
import glassredis.store.Key;
import glassredis.store.Keyspace;

import java.util.List;

/**
 * {@code HSET key field value [field value ...]} — 필드에 값을 넣고, <b>새로 생긴</b> 필드 수를 준다.
 * 이미 있던 필드의 값을 바꾼 건 세지 않는다. 키가 없으면 새 Hash 를 만든다.
 *
 * <p>예전에는 여러 필드를 넣는 명령이 {@code HMSET} 으로 따로 있었는데, Redis 4 부터 {@code HSET} 이 받는다.
 */
public final class HsetCommand implements Command {

    @Override
    public String name() {
        return "HSET";
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        if (args.size() < 3 || args.size() % 2 == 0) {
            return Errors.wrongNumberOfArguments(name());
        }
        Keyspace keyspace = ctx.keyspace();
        Key key = new Key(args.get(0));
        Entry entry = keyspace.get(key);

        HashValue hash;
        if (entry == null) {
            hash = new HashValue();
            keyspace.put(key, Entry.of(hash));
        } else if (entry.value() instanceof HashValue existing) {
            hash = existing;
        } else {
            return Errors.wrongType();
        }

        long created = 0;
        for (int i = 1; i < args.size(); i += 2) {
            if (hash.fields().put(new Key(args.get(i)), args.get(i + 1)) == null) {
                created++;
            }
        }
        return new RespValue.Int(created);
    }
}
