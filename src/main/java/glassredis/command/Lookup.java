package glassredis.command;

import glassredis.resp.RespValue;
import glassredis.store.Entry;
import glassredis.store.HashValue;
import glassredis.store.Key;
import glassredis.store.ListValue;
import glassredis.store.SetValue;
import glassredis.store.SortedSetValue;
import glassredis.store.StringValue;
import glassredis.store.Value;

/**
 * 자료형 확인 조회 - lookupKeyReadOrReply·lookupKeyWriteOrReply + checkType
 * - 읽기 명령은 string·list… (lookupKeyRead), 키를 고치는 명령은 stringWrite·listWrite… (lookupKeyWrite)
 * - 쓰기 조회는 쓰기 가능한 레플리카에서 만료 키를 실제로 지움 (EXPIRE_FORCE_DELETE_EXPIRED)
 * - 다른 자료형: reply 에 WRONGTYPE
 * - 없는 키: value·entry null, reply 는 넘긴 ifMissing (null 이면 이어서 진행 - 새로 만드는 쓰기 명령)
 * - 있는 키: value·entry 채움, reply null
 *
 * <pre>
 *   Lookup&lt;ListValue&gt; found = Lookup.list(ctx, key, RespValue.NIL);
 *   if (found.reply() != null) {
 *       return found.reply();
 *   }
 * </pre>
 *
 * @param entry 찾은 값과 그 조회가 읽은 만료 시각
 */
public record Lookup<T extends Value>(Entry entry, T value, RespValue reply) {

    public static Lookup<StringValue> string(Context ctx, Key key, RespValue ifMissing) {
        return of(ctx, key, StringValue.class, ifMissing, false);
    }

    public static Lookup<ListValue> list(Context ctx, Key key, RespValue ifMissing) {
        return of(ctx, key, ListValue.class, ifMissing, false);
    }

    public static Lookup<HashValue> hash(Context ctx, Key key, RespValue ifMissing) {
        return of(ctx, key, HashValue.class, ifMissing, false);
    }

    public static Lookup<SetValue> set(Context ctx, Key key, RespValue ifMissing) {
        return of(ctx, key, SetValue.class, ifMissing, false);
    }

    public static Lookup<SortedSetValue> zset(Context ctx, Key key, RespValue ifMissing) {
        return of(ctx, key, SortedSetValue.class, ifMissing, false);
    }

    public static Lookup<StringValue> stringWrite(Context ctx, Key key, RespValue ifMissing) {
        return of(ctx, key, StringValue.class, ifMissing, true);
    }

    public static Lookup<ListValue> listWrite(Context ctx, Key key, RespValue ifMissing) {
        return of(ctx, key, ListValue.class, ifMissing, true);
    }

    public static Lookup<HashValue> hashWrite(Context ctx, Key key, RespValue ifMissing) {
        return of(ctx, key, HashValue.class, ifMissing, true);
    }

    public static Lookup<SetValue> setWrite(Context ctx, Key key, RespValue ifMissing) {
        return of(ctx, key, SetValue.class, ifMissing, true);
    }

    public static Lookup<SortedSetValue> zsetWrite(Context ctx, Key key, RespValue ifMissing) {
        return of(ctx, key, SortedSetValue.class, ifMissing, true);
    }

    private static <T extends Value> Lookup<T> of(Context ctx, Key key, Class<T> type, RespValue ifMissing,
                                                  boolean write) {
        Entry entry = write ? ctx.keyspace().getWrite(key) : ctx.keyspace().get(key);
        if (entry == null) {
            return new Lookup<>(null, null, ifMissing);
        }
        if (!type.isInstance(entry.value())) {
            return new Lookup<>(entry, null, Errors.wrongType());
        }
        return new Lookup<>(entry, type.cast(entry.value()), null);
    }
}
