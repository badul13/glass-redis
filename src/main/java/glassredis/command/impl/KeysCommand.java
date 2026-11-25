package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.StringMatch;
import glassredis.resp.RespValue;
import glassredis.store.Keyspace;

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
        byte[] pattern = args.get(0);
        boolean allKeys = pattern.length == 1 && pattern[0] == '*';
        List<RespValue> keys = new ArrayList<>();
        // keyIsExpired - 로딩 중이면 만료 판단 안 함, 시각은 명령 시작 스냅샷 (commandTimeSnapshot)
        // 맞는 키마다 expires 를 dictFind 한 번 - 본 dict 순회는 rehash 없음 (safe iterator)
        Keyspace keyspace = ctx.keyspace();
        keyspace.forEach((key, entry) -> {
            if ((allKeys || StringMatch.matches(pattern, key.bytes(), false)) && !keyspace.isExpired(key)) {
                keys.add(new RespValue.BulkString(key.bytes()));
            }
        });
        return new RespValue.Array(keys);
    }
}
