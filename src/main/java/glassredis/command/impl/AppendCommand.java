package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.resp.RespReader;
import glassredis.resp.RespValue;
import glassredis.store.Entry;
import glassredis.store.Key;
import glassredis.store.StringValue;

import java.util.Arrays;
import java.util.List;

/**
 * APPEND key value - 붙인 뒤 길이, 만료 시각 유지
 * 매번 새 배열로 복사 - 반복 시 O(n²) (Redis는 SDS 여유 공간으로 회피)
 */
public final class AppendCommand implements Command {

    @Override
    public String name() {
        return "APPEND";
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        if (args.size() != 2) {
            return Errors.wrongNumberOfArguments(name());
        }
        Key key = new Key(args.get(0));
        byte[] suffix = args.get(1);
        Entry entry = ctx.keyspace().get(key);

        if (entry == null) {
            ctx.keyspace().put(key, Entry.of(suffix));
            return new RespValue.Int(suffix.length);
        }

        if (!(entry.value() instanceof StringValue string)) {
            return Errors.wrongType();
        }
        byte[] current = string.bytes();
        long joinedLength = (long) current.length + suffix.length;
        // 분할 APPEND로 벌크 길이 한도 우회 방지
        if (joinedLength > RespReader.MAX_BULK_LENGTH) {
            return Errors.stringTooLong();
        }
        byte[] joined = Arrays.copyOf(current, (int) joinedLength);
        System.arraycopy(suffix, 0, joined, current.length, suffix.length);
        // APPEND 결과는 길이 무관 raw - Redis와 동일
        ctx.keyspace().put(key, entry.withValue(StringValue.raw(joined)));
        return new RespValue.Int(joinedLength);
    }
}
