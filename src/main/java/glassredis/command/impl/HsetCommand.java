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

/** HSET key field value [field value ...] - 새 필드 수만 집계, 값만 바뀐 필드 제외 */
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

        // 쌍 512개 초과 또는 64바이트 초과 값 존재 시 삽입 전 hashtable로 선전환
        hash.prepareForSet(args.subList(1, args.size()));
        long created = 0;
        for (int i = 1; i < args.size(); i += 2) {
            if (hash.set(new Key(args.get(i)), args.get(i + 1))) {
                created++;
            }
        }
        return new RespValue.Int(created);
    }
}
