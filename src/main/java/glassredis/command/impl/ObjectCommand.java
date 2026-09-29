package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.resp.RespValue;
import glassredis.store.Entry;
import glassredis.store.Key;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * {@code OBJECT ENCODING key} — 값이 지금 어떤 모양으로 담겨 있는지. 키가 없으면 nil.
 *
 * <pre>
 *   string  int · embstr · raw
 *   list    listpack · quicklist
 *   hash    listpack · hashtable
 *   set     intset · listpack · hashtable
 *   zset    listpack · skiplist
 * </pre>
 * 같은 자료형도 작을 때는 촘촘한 모양으로, 커지면 빠른 모양으로 담긴다. 이 명령으로 그 전환을 밖에서 볼 수 있다.
 * glass-redis 가 실제 Redis 와 같은 기준으로 바꾸는지 대조할 때도 이 명령을 쓴다.
 *
 * <p>실제 Redis 의 다른 하위 명령({@code FREQ}, {@code IDLETIME}, {@code REFCOUNT}, {@code HELP})은 아직 없다.
 * 모르는 하위 명령과 같은 에러를 준다.
 */
public final class ObjectCommand implements Command {

    @Override
    public String name() {
        return "OBJECT";
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        if (args.isEmpty()) {
            return Errors.wrongNumberOfArguments(name());
        }
        String subcommand = new String(args.get(0), StandardCharsets.UTF_8);
        if (!subcommand.toUpperCase(Locale.ROOT).equals("ENCODING")) {
            return Errors.unknownSubcommand(subcommand, name());
        }
        if (args.size() != 2) {
            return Errors.wrongNumberOfArguments("object|encoding");
        }
        Entry entry = ctx.keyspace().get(new Key(args.get(1)));
        return entry == null ? RespValue.NIL : RespValue.BulkString.of(entry.value().encoding());
    }
}
