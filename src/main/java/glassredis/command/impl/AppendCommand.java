package glassredis.command.impl;

import glassredis.command.Command;
import glassredis.command.Context;
import glassredis.command.Errors;
import glassredis.command.Lookup;
import glassredis.resp.RespReader;
import glassredis.resp.RespValue;
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
    public int arity() {
        return 3;
    }

    @Override
    public boolean writes() {
        return true;
    }

    /**
     * checkStringLength - 분할 APPEND 로 벌크 길이 한도 우회 방지
     * 마스터·AOF 로딩은 생략 - 이미 받아들여진 명령, 거부 시 마스터와 데이터 어긋남
     */
    static boolean exceedsLimit(long size, long append, boolean mustObey) {
        return !mustObey && size + append > RespReader.MAX_BULK_LENGTH;
    }

    @Override
    public RespValue execute(Context ctx, List<byte[]> args) {
        Key key = new Key(args.get(0));
        byte[] suffix = args.get(1);
        Lookup<StringValue> found = Lookup.stringWrite(ctx, key, null);
        if (found.reply() != null) {
            return found.reply();
        }
        if (found.value() == null) {
            // dbAdd - 인자 그대로 tryObjectEncoding
            ctx.keyspace().add(key, StringValue.of(suffix));
            ctx.dirty();
            return new RespValue.Int(suffix.length);
        }

        byte[] current = found.value().bytes();
        long joinedLength = (long) current.length + suffix.length;
        if (exceedsLimit(current.length, suffix.length, ctx.keyspace().mustObeyClient())) {
            return Errors.stringTooLong();
        }
        byte[] joined = Arrays.copyOf(current, (int) joinedLength);
        System.arraycopy(suffix, 0, joined, current.length, suffix.length);
        // APPEND 결과는 길이 무관 raw - dbUnshareStringValue
        // 이미 raw 면 같은 객체에 이어 붙임 (dict 재조회 없음), 아니면 raw 새 객체로 dbReplaceValue
        if (found.value().form() == StringValue.Form.RAW) {
            ctx.keyspace().replaceLookedUpValue(key, StringValue.raw(joined));
        } else {
            ctx.keyspace().replaceValue(key, StringValue.raw(joined));
        }
        ctx.dirty();
        return new RespValue.Int(joinedLength);
    }
}
