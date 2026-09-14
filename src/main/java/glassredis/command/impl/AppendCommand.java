package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.resp.RespReader;
import glassredis.resp.RespValue;
import glassredis.store.Entry;
import glassredis.store.Key;

import java.util.Arrays;
import java.util.List;

/**
 * {@code APPEND key value} — 기존 값 뒤에 이어 붙이고, 붙인 뒤의 길이를 준다. 키가 없으면 새로 만든다.
 *
 * <p>{@code byte[]} 는 크기를 늘릴 수 없어서 매번 새 배열을 만들어 복사한다. 같은 키에 {@code APPEND} 를 n 번 하면
 * 복사량이 n² 에 비례한다. 실제 Redis 는 여유 공간을 미리 잡아두는 문자열 구조(SDS)로 이 비용을 줄이는데,
 * 여기서는 단순함을 택했다.
 *
 * <p>값을 고치는 명령이므로 만료 시각은 그대로 둔다.
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

        byte[] current = entry.value();
        long joinedLength = (long) current.length + suffix.length;
        // 한 번에 받을 수 있는 벌크 문자열 크기를 넘는 값은 만들지 않는다. 조금씩 붙여서 한도를 우회하는 걸 막는다.
        if (joinedLength > RespReader.MAX_BULK_LENGTH) {
            return Errors.stringTooLong();
        }
        byte[] joined = Arrays.copyOf(current, (int) joinedLength);
        System.arraycopy(suffix, 0, joined, current.length, suffix.length);
        ctx.keyspace().put(key, entry.withValue(joined));
        return new RespValue.Int(joinedLength);
    }
}
