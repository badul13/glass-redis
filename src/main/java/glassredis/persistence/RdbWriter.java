package glassredis.persistence;

import glassredis.store.HashValue;
import glassredis.store.Keyspace;
import glassredis.store.ListValue;
import glassredis.store.SetValue;
import glassredis.store.SkipList;
import glassredis.store.SortedSetValue;
import glassredis.store.StringValue;
import glassredis.store.Value;
import glassredis.store.encoding.Listpack;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 키스페이스 → RDB 바이트 - rdbSaveRio
 * 작은 인코딩(listpack, intset)은 메모리 속 바이트 그대로 한 덩어리, 큰 인코딩은 원소 하나씩
 * 키 순서는 dict 버킷 순 - 해시 시드가 실행마다 달라 Redis 결과와 순서 비교 불가
 * 만료됐지만 안 지워진 키도 포함 (Redis와 동일) - 읽는 쪽이 지난 만료 키를 버림
 */
public final class RdbWriter {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private RdbWriter() {
    }

    /**
     * 실행 스레드에서 호출 - 순회 중 키스페이스 변경 없음
     *
     * @param ctimeSeconds ctime 보조 필드 - 에포크 초
     * @param usedMemory   used-mem 보조 필드 - Redis는 zmalloc 사용량, 여기는 JVM 힙 사용량
     * @param aofBase      AOF base 파일용 - aof-base 보조 필드 1 (RDBFLAGS_AOF_PREAMBLE)
     */
    public static byte[] write(Keyspace keyspace, long ctimeSeconds, long usedMemory, boolean aofBase) {
        RdbWriter writer = new RdbWriter();
        writer.header(ctimeSeconds, usedMemory, aofBase);
        writer.database(keyspace);
        return writer.finish();
    }

    /** "REDIS0011" + 보조 필드 - rdbSaveInfoAuxFields, 복제 정보(rsi) 없음 */
    private void header(long ctimeSeconds, long usedMemory, boolean aofBase) {
        out.writeBytes(String.format("REDIS%04d", RdbFormat.VERSION).getBytes(StandardCharsets.US_ASCII));
        aux("redis-ver", RdbFormat.REDIS_VERSION);
        aux("redis-bits", "64");
        aux("ctime", Long.toString(ctimeSeconds));
        aux("used-mem", Long.toString(usedMemory));
        aux("aof-base", aofBase ? "1" : "0");
    }

    private void aux(String key, String value) {
        type(RdbFormat.OPCODE_AUX);
        rawString(key.getBytes(StandardCharsets.US_ASCII));
        rawString(value.getBytes(StandardCharsets.US_ASCII));
    }

    /** rdbSaveDb - 비었으면 SELECTDB도 생략 */
    private void database(Keyspace keyspace) {
        if (keyspace.size() == 0) {
            return;
        }
        type(RdbFormat.OPCODE_SELECTDB);
        length(0);
        type(RdbFormat.OPCODE_RESIZEDB);
        length(keyspace.size());
        length(keyspace.expiringKeyCount());
        keyspace.forEach((key, entry) -> {
            // rdbSaveKeyValuePair - 만료, 타입, 키, 값 순
            if (entry.hasExpiry()) {
                type(RdbFormat.OPCODE_EXPIRETIME_MS);
                littleEndian(entry.expireAtMillis(), 8);
            }
            type(objectType(entry.value()));
            rawString(key.bytes());
            object(entry.value());
        });
    }

    /** EOF + 앞 전체의 CRC64 */
    private byte[] finish() {
        type(RdbFormat.OPCODE_EOF);
        byte[] body = out.toByteArray();
        long crc = Crc64.update(0, body, 0, body.length);
        littleEndian(crc, 8);
        return out.toByteArray();
    }

    /** rdbSaveObjectType */
    private static int objectType(Value value) {
        return switch (value) {
            case StringValue ignored -> RdbFormat.TYPE_STRING;
            // listpack 하나짜리 리스트도 노드 하나인 quicklist로
            case ListValue ignored -> RdbFormat.TYPE_LIST_QUICKLIST_2;
            case SetValue set -> set.intset() != null ? RdbFormat.TYPE_SET_INTSET
                    : set.listpack() != null ? RdbFormat.TYPE_SET_LISTPACK : RdbFormat.TYPE_SET;
            case SortedSetValue zset -> zset.listpack() != null ? RdbFormat.TYPE_ZSET_LISTPACK : RdbFormat.TYPE_ZSET_2;
            case HashValue hash -> hash.listpack() != null ? RdbFormat.TYPE_HASH_LISTPACK : RdbFormat.TYPE_HASH;
        };
    }

    /** rdbSaveObject */
    private void object(Value value) {
        switch (value) {
            case StringValue string -> stringObject(string);
            case ListValue list -> {
                // [노드 수] ([PACKED] [listpack 바이트])...
                var nodes = list.nodes();
                length(nodes.size());
                for (Listpack node : nodes) {
                    length(RdbFormat.CONTAINER_PACKED);
                    rawString(node.rawBytes());
                }
            }
            case SetValue set -> {
                if (set.intset() != null) {
                    rawString(set.intset().rawBytes());
                } else if (set.listpack() != null) {
                    rawString(set.listpack().rawBytes());
                } else {
                    length(set.dict().size());
                    set.forEach(this::rawString);
                }
            }
            case SortedSetValue zset -> {
                if (zset.listpack() != null) {
                    rawString(zset.listpack().rawBytes());
                } else {
                    // 큰 점수부터 - 읽는 쪽 skiplist 삽입이 항상 맨 앞에서 끝나 O(1)
                    SkipList order = zset.order();
                    length(order.length());
                    for (SkipList.Node node = order.last(); node != null; node = node.previous()) {
                        rawString(node.member());
                        littleEndian(Double.doubleToRawLongBits(node.score()), 8);
                    }
                }
            }
            case HashValue hash -> {
                if (hash.listpack() != null) {
                    rawString(hash.listpack().rawBytes());
                } else {
                    length(hash.dict().size());
                    hash.forEach((field, fieldValue) -> {
                        rawString(field);
                        rawString(fieldValue);
                    });
                }
            }
        }
    }

    /** rdbSaveStringObject - int 인코딩은 다시 해석 없이 정수로 */
    private void stringObject(StringValue string) {
        if (string.form() == StringValue.Form.INT) {
            long value = Long.parseLong(new String(string.bytes(), StandardCharsets.US_ASCII));
            if (!encodedInteger(value)) {
                // 32비트 밖 - 10진수 문자열 그대로, 20자 이하라 압축 시도 없음
                length(string.bytes().length);
                out.writeBytes(string.bytes());
            }
            return;
        }
        rawString(string.bytes());
    }

    /**
     * rdbSaveRawString - 11자 이하 정수 표기면 정수, 20바이트 초과면 LZF 시도, 아니면 [길이][내용]
     */
    private void rawString(byte[] s) {
        if (s.length <= 11) {
            Long value = Listpack.toInt64(s);
            if (value != null && encodedInteger(value)) {
                return;
            }
        }
        // rdbcompression yes 고정, 20바이트 이하는 "aaaa…"도 못 줄임
        if (s.length > 20 && lzf(s)) {
            return;
        }
        length(s.length);
        out.writeBytes(s);
    }

    /** rdbSaveLzfStringObject - 4바이트 이상 줄어야 사용 */
    private boolean lzf(byte[] s) {
        byte[] compressed = Lzf.compress(s, s.length - 4);
        if (compressed == null) {
            return false;
        }
        out.write(RdbFormat.ENCVAL << 6 | RdbFormat.ENC_LZF);
        length(compressed.length);
        length(s.length);
        out.writeBytes(compressed);
        return true;
    }

    /** rdbEncodeInteger - 32비트 안이면 1·2·4바이트 리틀 엔디언 */
    private boolean encodedInteger(long value) {
        if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            out.write(RdbFormat.ENCVAL << 6 | RdbFormat.ENC_INT8);
            littleEndian(value, 1);
        } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            out.write(RdbFormat.ENCVAL << 6 | RdbFormat.ENC_INT16);
            littleEndian(value, 2);
        } else if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
            out.write(RdbFormat.ENCVAL << 6 | RdbFormat.ENC_INT32);
            littleEndian(value, 4);
        } else {
            return false;
        }
        return true;
    }

    /** rdbSaveLen - 6비트, 14비트, 32비트, 64비트 (뒤 둘은 빅 엔디언) */
    private void length(long len) {
        if (len < 1 << 6) {
            out.write((int) len | RdbFormat.LEN_6BIT << 6);
        } else if (len < 1 << 14) {
            out.write((int) (len >>> 8) | RdbFormat.LEN_14BIT << 6);
            out.write((int) len & 0xFF);
        } else if (len <= 0xFFFFFFFFL) {
            out.write(RdbFormat.LEN_32BIT);
            bigEndian(len, 4);
        } else {
            out.write(RdbFormat.LEN_64BIT);
            bigEndian(len, 8);
        }
    }

    private void type(int type) {
        out.write(type);
    }

    private void littleEndian(long value, int bytes) {
        for (int i = 0; i < bytes; i++) {
            out.write((int) (value >>> (8 * i)) & 0xFF);
        }
    }

    private void bigEndian(long value, int bytes) {
        for (int i = bytes - 1; i >= 0; i--) {
            out.write((int) (value >>> (8 * i)) & 0xFF);
        }
    }
}
