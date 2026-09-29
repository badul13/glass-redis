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
 * OBJECT ENCODING key - 인코딩 이름, 키 없으면 nil
 * 나머지 하위 명령(FREQ, IDLETIME, REFCOUNT, HELP) - 모르는 하위 명령 처리
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
