package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.command.StringMatch;
import glassredis.resp.RespValue;

import java.util.ArrayList;
import java.util.List;

/**
 * KEYS pattern - keysCommand
 * - 만료 키: 응답에서 빼기만, 삭제 없음 (keyIsExpired) - 순회 중 키스페이스 수정 금지와도 맞물림
 * - 패턴이 정확히 "*" 이면 매칭 생략
 */
public final class KeysCommand implements Command {

    @Override
    public String name() {
        return "KEYS";
    }

    @Override
    public int arity() {
        return 2;
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        if (args.size() != 1) {
            return Errors.wrongNumberOfArguments(name());
        }
        byte[] pattern = args.get(0);
        boolean allKeys = pattern.length == 1 && pattern[0] == '*';
        List<RespValue> keys = new ArrayList<>();
        ctx.keyspace().forEach((key, entry) -> {
            if ((allKeys || StringMatch.matches(pattern, key.bytes(), false))
                    && !ctx.keyspace().isExpired(key)) {
                keys.add(new RespValue.BulkString(key.bytes()));
            }
        });
        return new RespValue.Array(keys);
    }
}
