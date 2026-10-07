package glassredis.persistence;

import glassredis.store.HashValue;
import glassredis.store.Key;
import glassredis.store.Keyspace;
import glassredis.store.ListValue;
import glassredis.store.SetValue;
import glassredis.store.SortedSetValue;
import glassredis.store.StringValue;
import glassredis.store.Value;
import glassredis.store.encoding.Doubles;
import glassredis.store.encoding.Listpack;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * AOF 재작성 - 지금 데이터를 다시 만드는 최소 명령 (rewriteAppendOnlyFileRio)
 * 키 순서는 dict 버킷 순 - 해시 시드가 실행마다 달라 비교 대상 아님
 * 만료됐지만 안 지워진 키도 포함 (Redis와 동일) - 복구 후 만료 처리가 DEL
 */
public final class AofRewriter {

    /** AOF_REWRITE_ITEMS_PER_CMD */
    static final int ITEMS_PER_COMMAND = 64;

    private AofRewriter() {
    }

    /** 실행 스레드에서 호출 - 순회 중 키스페이스 변경 없음 */
    public static byte[] rewrite(Keyspace keyspace) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (keyspace.size() == 0) {
            return out.toByteArray();
        }
        // SELECT 0 - DB 하나뿐
        AofFormat.header(out, '*', 2);
        AofFormat.bulk(out, "SELECT");
        AofFormat.bulk(out, "0");
        keyspace.forEach((key, entry) -> {
            writeValue(out, key.bytes(), entry.value());
            if (entry.hasExpiry()) {
                AofFormat.header(out, '*', 3);
                AofFormat.bulk(out, "PEXPIREAT");
                AofFormat.bulk(out, key.bytes());
                AofFormat.bulk(out, Long.toString(entry.expireAtMillis()));
            }
        });
        return out.toByteArray();
    }

    private static void writeValue(ByteArrayOutputStream out, byte[] key, Value value) {
        switch (value) {
            case StringValue string -> {
                AofFormat.header(out, '*', 3);
                AofFormat.bulk(out, "SET");
                AofFormat.bulk(out, key);
                AofFormat.bulk(out, string.bytes());
            }
            case ListValue list -> {
                List<byte[]> items = new ArrayList<>();
                for (Listpack node : list.nodes()) {
                    for (int p = node.first(); p != -1; p = node.next(p)) {
                        items.add(node.get(p));
                    }
                }
                writeChunks(out, "RPUSH", key, items, 1);
            }
            case SetValue set -> {
                List<byte[]> items = new ArrayList<>(set.size());
                set.forEach(items::add);
                writeChunks(out, "SADD", key, items, 1);
            }
            case SortedSetValue zset -> writeChunks(out, "ZADD", key, sortedSetItems(zset), 2);
            case HashValue hash -> {
                List<byte[]> items = new ArrayList<>(hash.size() * 2);
                hash.forEach((field, fieldValue) -> {
                    items.add(field);
                    items.add(fieldValue);
                });
                writeChunks(out, "HMSET", key, items, 2);
            }
            default -> throw new IllegalStateException("알 수 없는 자료형: " + value.typeName());
        }
    }

    /** [점수, 멤버, ...] - listpack은 점수 순, skiplist는 dict 버킷 순 (rewriteSortedSetObject) */
    private static List<byte[]> sortedSetItems(SortedSetValue zset) {
        List<byte[]> items = new ArrayList<>(zset.size() * 2);
        Listpack listpack = zset.listpack();
        if (listpack != null) {
            for (int p = listpack.first(); p != -1; p = listpack.next(listpack.next(p))) {
                byte[] member = listpack.get(p);
                double score = Doubles.parse(listpack.get(listpack.next(p)));
                items.add(Doubles.format(score));
                items.add(member);
            }
        } else {
            zset.dict().forEach((Key member, Double score) -> {
                items.add(Doubles.format(score));
                items.add(member.bytes());
            });
        }
        return items;
    }

    /**
     * 원소 64개마다 명령 하나 - 큰 값 하나로 복구 시 메모리 폭증 방지
     *
     * @param width 원소 하나의 인자 수 - 해시·정렬 셋은 2
     */
    private static void writeChunks(ByteArrayOutputStream out, String command, byte[] key, List<byte[]> items,
                                    int width) {
        int elements = items.size() / width;
        for (int start = 0; start < elements; start += ITEMS_PER_COMMAND) {
            int count = Math.min(ITEMS_PER_COMMAND, elements - start);
            AofFormat.header(out, '*', 2L + (long) count * width);
            AofFormat.bulk(out, command);
            AofFormat.bulk(out, key);
            for (int i = start * width; i < (start + count) * width; i++) {
                AofFormat.bulk(out, items.get(i));
            }
        }
    }
}
