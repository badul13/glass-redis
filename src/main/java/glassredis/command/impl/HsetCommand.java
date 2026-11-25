package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.command.Lookup;
import glassredis.resp.RespValue;
import glassredis.store.HashValue;
import glassredis.store.Key;
import glassredis.store.Keyspace;

import java.util.List;

/**
 * HSET key field value [field value ...] - 새 필드 수만 집계, 값만 바뀐 필드 제외
 * HMSET - 같은 동작에 응답만 OK, AOF 재작성 결과가 쓰는 형태
 */
public final class HsetCommand implements Command {

    private final String name;
    private final boolean replyOk;

    public HsetCommand() {
        this("HSET", false);
    }

    private HsetCommand(String name, boolean replyOk) {
        this.name = name;
        this.replyOk = replyOk;
    }

    public static HsetCommand hmset() {
        return new HsetCommand("HMSET", true);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public int arity() {
        return -4;
    }

    @Override
    public boolean writes() {
        return true;
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        // 필드·값 짝 검사 - arity 로 표현 불가
        if (args.size() % 2 == 0) {
            return Errors.wrongNumberOfArguments(name());
        }
        Keyspace keyspace = ctx.keyspace();
        Key key = new Key(args.get(0));
        Lookup<HashValue> found = Lookup.hashWrite(ctx, key, null);
        if (found.reply() != null) {
            return found.reply();
        }
        HashValue hash;
        if (found.value() == null) {
            hash = new HashValue();
            keyspace.add(key, hash);
        } else {
            hash = found.value();
        }

        // 쌍 512개 초과 또는 64바이트 초과 값 존재 시 삽입 전 hashtable로 선전환
        hash.prepareForSet(args.subList(1, args.size()));
        long created = 0;
        for (int i = 1; i < args.size(); i += 2) {
            if (hash.set(new Key(args.get(i)), args.get(i + 1))) {
                created++;
            }
        }
        ctx.dirty((args.size() - 1) / 2);
        return replyOk ? RespValue.OK : new RespValue.Int(created);
    }
}
