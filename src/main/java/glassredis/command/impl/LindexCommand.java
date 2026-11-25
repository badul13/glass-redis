package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.command.Lookup;
import glassredis.command.Numbers;
import glassredis.resp.RespValue;
import glassredis.store.Key;
import glassredis.store.ListValue;

import java.util.List;
import java.util.OptionalLong;

/** LINDEX key index - 음수는 뒤에서부터, 범위 밖이면 nil, 가까운 쪽 끝에서 탐색 */
public final class LindexCommand implements Command {

    @Override
    public String name() {
        return "LINDEX";
    }

    @Override
    public int arity() {
        return 3;
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        // lindexCommand 순서 - 키 조회·자료형 확인 뒤 인덱스 해석
        Lookup<ListValue> found = Lookup.list(ctx, new Key(args.get(0)), RespValue.NIL);
        if (found.reply() != null) {
            return found.reply();
        }
        ListValue list = found.value();
        OptionalLong parsed = Numbers.parseLong(args.get(1));
        if (parsed.isEmpty()) {
            return Errors.notAnInteger();
        }

        byte[] element = list.index(parsed.getAsLong());
        return element == null ? RespValue.NIL : new RespValue.BulkString(element);
    }
}
