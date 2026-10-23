package glassredis.persistence;

import glassredis.store.Entry;
import glassredis.store.HashValue;
import glassredis.store.Key;
import glassredis.store.Keyspace;
import glassredis.store.ListValue;
import glassredis.store.SetValue;
import glassredis.store.SortedSetValue;
import glassredis.store.StringValue;
import glassredis.store.Value;
import glassredis.store.encoding.Intset;
import glassredis.store.encoding.Listpack;
import glassredis.store.encoding.Quicklist;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * RDB 바이트 → 키스페이스 - rdbLoadRio, rdbLoadObject
 * 작은 인코딩 덩어리는 복사 없이 그대로 값으로, 한도 넘으면 그 자리에서 큰 인코딩으로 전환
 * 지난 만료 키는 버림 (마스터 기준, AOF base 제외), 빈 키는 건너뜀, 같은 키 두 번이면 실패
 * 미지원 - ziplist·zipmap 시절 타입(Redis 7 이전 파일), 스트림, 모듈, 함수, quicklist plain 노드
 */
public final class RdbLoader {

    /** RDB_OK, RDB_NOT_EXIST, RDB_FAILED */
    public enum Status { OK, NOT_EXIST, FAILED }

    /**
     * @param keys         넣은 키 수 (rdb_last_load_keys_loaded)
     * @param expired      지난 만료로 버린 키 수 (rdb_last_load_keys_expired)
     * @param createdBy    redis-ver 보조 필드 - 없으면 null
     * @param error        FAILED 사유 - 아니면 null
     */
    public record Result(Status status, long keys, long expired, long emptySkipped, long bytes, long elapsedMillis,
                         String createdBy, String error) {
    }

    /** 손상 - rdbReportCorruptRDB, rdbReportReadError */
    private static final class CorruptRdbException extends RuntimeException {
        CorruptRdbException(String message) {
            super(message);
        }
    }

    private final byte[] data;
    private final Keyspace keyspace;
    private final long now;
    /** RDBFLAGS_AOF_PREAMBLE - 지난 만료 키도 넣음, 지우는 건 복구 후 만료 처리 */
    private final boolean aofPreamble;
    private int pos;

    private long keys;
    private long expired;
    private long emptySkipped;
    private String createdBy;

    private RdbLoader(byte[] data, Keyspace keyspace, boolean aofPreamble) {
        this.data = data;
        this.keyspace = keyspace;
        this.now = keyspace.now();
        this.aofPreamble = aofPreamble;
    }

    /** rdbLoad - 파일 없으면 NOT_EXIST, 실패해도 그때까지 넣은 키는 남음 (기동 중단 전제) */
    public static Result load(Path file, Keyspace keyspace) {
        long start = System.currentTimeMillis();
        byte[] data;
        try {
            data = Files.readAllBytes(file);
        } catch (NoSuchFileException e) {
            return new Result(Status.NOT_EXIST, 0, 0, 0, 0, 0, null, null);
        } catch (IOException e) {
            return new Result(Status.FAILED, 0, 0, 0, 0, 0, null,
                    "can't open the RDB file " + file + " for reading: " + e.getMessage());
        }
        return load(data, keyspace, start);
    }

    static Result load(byte[] data, Keyspace keyspace) {
        return load(data, keyspace, System.currentTimeMillis());
    }

    private static Result load(byte[] data, Keyspace keyspace, long start) {
        return new RdbLoader(data, keyspace, false).result(start);
    }

    /**
     * AOF base 의 RDB 부분 - loadSingleAppendOnlyFile 의 rdbLoadRio(RDBFLAGS_AOF_PREAMBLE)
     *
     * @param end 체크섬 바로 뒤 - 이어지는 AOF 명령의 시작
     */
    record Preamble(Result result, int end) {
    }

    static Preamble loadAofPreamble(byte[] data, Keyspace keyspace) {
        RdbLoader loader = new RdbLoader(data, keyspace, true);
        Result result = loader.result(System.currentTimeMillis());
        return new Preamble(result, loader.pos);
    }

    private Result result(long start) {
        String error = null;
        try {
            run();
        } catch (CorruptRdbException e) {
            error = e.getMessage();
        } catch (RuntimeException e) {
            // listpack 속이 깨져 걷다가 범위를 벗어난 경우 등
            error = "corrupt RDB at offset " + pos + ": " + e;
        }
        return new Result(error == null ? Status.OK : Status.FAILED, keys, expired, emptySkipped, data.length,
                System.currentTimeMillis() - start, createdBy, error);
    }

    /** rdbLoadRioWithLoadingCtx */
    private void run() {
        byte[] magic = bytes(9);
        if (!new String(magic, 0, 5, StandardCharsets.US_ASCII).equals("REDIS")) {
            throw new CorruptRdbException("Wrong signature trying to load DB from file");
        }
        int version = atoi(magic, 5);
        if (version < 1 || version > RdbFormat.VERSION) {
            throw new CorruptRdbException("Can't handle RDB format version " + version);
        }

        long expireAt = Entry.NO_EXPIRY;
        while (true) {
            int type = u8();
            switch (type) {
                case RdbFormat.OPCODE_EXPIRETIME -> {
                    // 옛 형식 - 초 단위 4바이트
                    expireAt = (int) littleEndian(4) * 1000L;
                    continue;
                }
                case RdbFormat.OPCODE_EXPIRETIME_MS -> {
                    expireAt = littleEndian(8);
                    continue;
                }
                case RdbFormat.OPCODE_FREQ -> {
                    u8(); // LFU - maxmemory 정책 없음
                    continue;
                }
                case RdbFormat.OPCODE_IDLE -> {
                    length(); // LRU - maxmemory 정책 없음
                    continue;
                }
                case RdbFormat.OPCODE_EOF -> {
                    checksum(version);
                    return;
                }
                case RdbFormat.OPCODE_SELECTDB -> {
                    long db = length();
                    if (db != 0) {
                        throw new CorruptRdbException("DB " + db + " 의 데이터 - DB 0 하나만 지원");
                    }
                    continue;
                }
                case RdbFormat.OPCODE_RESIZEDB -> {
                    long size = length();
                    long expires = length();
                    keyspace.expandForLoading(size, expires);
                    continue;
                }
                case RdbFormat.OPCODE_AUX -> {
                    String key = new String(string(), StandardCharsets.US_ASCII);
                    byte[] value = string();
                    if (key.equalsIgnoreCase("redis-ver")) {
                        createdBy = new String(value, StandardCharsets.US_ASCII);
                    }
                    // 그 밖의 보조 필드는 로그용 - 복제 정보는 복제 단계에서
                    continue;
                }
                case RdbFormat.OPCODE_MODULE_AUX ->
                        throw new CorruptRdbException("The RDB file contains AUX module data I can't load");
                case RdbFormat.OPCODE_FUNCTION_PRE_GA, RdbFormat.OPCODE_FUNCTION2 ->
                        throw new CorruptRdbException("함수 라이브러리 데이터 - 함수 미지원");
                default -> {
                    // 그 밖은 값 타입 - 키, 값 순
                }
            }

            byte[] key = string();
            Value value = object(type);
            if (value == null) {
                emptySkipped++;
            } else if (!aofPreamble && expireAt != Entry.NO_EXPIRY && expireAt < now) {
                expired++;
            } else {
                Key k = new Key(key);
                if (keyspace.peek(k) != null) {
                    throw new CorruptRdbException("RDB has duplicated key '"
                            + new String(key, StandardCharsets.UTF_8) + "' in DB 0");
                }
                keyspace.put(k, new Entry(value, expireAt));
                keys++;
            }
            expireAt = Entry.NO_EXPIRY;
        }
    }

    /** 버전 5부터 끝 8바이트 CRC64 - 0이면 체크섬 끈 채로 저장된 파일 */
    private void checksum(int version) {
        if (version < 5) {
            return;
        }
        int end = pos;
        long stored = littleEndian(8);
        if (stored != 0 && stored != Crc64.update(0, data, 0, end)) {
            throw new CorruptRdbException(String.format("Wrong RDB checksum expected: (%x) but got (%x)",
                    Crc64.update(0, data, 0, end), stored));
        }
    }

    /** rdbLoadObject - 빈 키면 null */
    private Value object(int type) {
        return switch (type) {
            case RdbFormat.TYPE_STRING -> StringValue.of(string());
            case RdbFormat.TYPE_LIST -> plainList();
            case RdbFormat.TYPE_SET -> plainSet();
            case RdbFormat.TYPE_ZSET, RdbFormat.TYPE_ZSET_2 -> plainSortedSet(type == RdbFormat.TYPE_ZSET_2);
            case RdbFormat.TYPE_HASH -> plainHash();
            case RdbFormat.TYPE_LIST_QUICKLIST_2 -> quicklist();
            case RdbFormat.TYPE_SET_INTSET -> SetValue.ofIntset(Intset.fromBytes(string()));
            case RdbFormat.TYPE_SET_LISTPACK -> SetValue.ofListpack(Listpack.fromBytes(string()));
            case RdbFormat.TYPE_ZSET_LISTPACK -> SortedSetValue.ofListpack(Listpack.fromBytes(string()));
            case RdbFormat.TYPE_HASH_LISTPACK -> HashValue.ofListpack(Listpack.fromBytes(string()));
            default -> throw new CorruptRdbException("지원하지 않는 RDB 타입 " + type
                    + (type >= 9 && type <= 14 ? " (ziplist·zipmap 시절 형식)" : ""));
        };
    }

    /** RDB_TYPE_LIST - 원소 하나씩 quicklist 뒤에 */
    private Value plainList() {
        long length = length();
        if (length == 0) {
            return null;
        }
        Quicklist quicklist = new Quicklist();
        for (long i = 0; i < length; i++) {
            quicklist.pushTail(string());
        }
        List<Listpack> nodes = new ArrayList<>(quicklist.nodeCount());
        for (Quicklist.Node node = quicklist.head(); node != null; node = node.next()) {
            nodes.add(node.listpack());
        }
        return ListValue.fromNodes(nodes);
    }

    /** RDB_TYPE_LIST_QUICKLIST_2 - [노드 수] ([컨테이너] [listpack])... */
    private Value quicklist() {
        long count = length();
        if (count == 0) {
            return null;
        }
        List<Listpack> nodes = new ArrayList<>();
        for (long i = 0; i < count; i++) {
            long container = length();
            if (container != RdbFormat.CONTAINER_PACKED && container != RdbFormat.CONTAINER_PLAIN) {
                throw new CorruptRdbException("Quicklist integrity check failed.");
            }
            byte[] node = string();
            if (node.length == 0) {
                throw new CorruptRdbException("Quicklist integrity check failed.");
            }
            if (container == RdbFormat.CONTAINER_PLAIN) {
                // 1GB 넘는 원소 하나짜리 노드 - 값 한도 512MB라 생길 일 없음
                throw new CorruptRdbException("quicklist plain 노드 미지원");
            }
            nodes.add(Listpack.fromBytes(node));
        }
        return ListValue.fromNodes(nodes);
    }

    /** RDB_TYPE_SET */
    private Value plainSet() {
        long length = length();
        if (length == 0) {
            return null;
        }
        SetValue set = SetValue.forLoading(length);
        int maxLength = 0;
        for (long i = 0; i < length; i++) {
            byte[] member = string();
            maxLength = Math.max(maxLength, member.length);
            if (!set.addLoaded(member, length, maxLength)) {
                throw new CorruptRdbException("Duplicate set members detected");
            }
        }
        return set;
    }

    /** RDB_TYPE_ZSET_2 - 점수 8바이트, RDB_TYPE_ZSET - 점수 문자열 */
    private Value plainSortedSet(boolean binaryScores) {
        long length = length();
        if (length == 0) {
            return null;
        }
        SortedSetValue zset = SortedSetValue.forLoading(length);
        int maxLength = 0;
        for (long i = 0; i < length; i++) {
            byte[] member = string();
            double score = binaryScores ? Double.longBitsToDouble(littleEndian(8)) : stringDouble();
            if (Double.isNaN(score)) {
                throw new CorruptRdbException("Zset with NAN score detected");
            }
            maxLength = Math.max(maxLength, member.length);
            if (!zset.addLoaded(member, score)) {
                throw new CorruptRdbException("Duplicate zset fields detected");
            }
        }
        zset.finishLoading(maxLength);
        return zset;
    }

    /** RDB_TYPE_HASH */
    private Value plainHash() {
        long length = length();
        if (length == 0) {
            return null;
        }
        HashValue hash = HashValue.forLoading(length);
        for (long remaining = length - 1; remaining >= 0; remaining--) {
            byte[] field = string();
            byte[] value = string();
            if (!hash.putLoaded(field, value, remaining)) {
                throw new CorruptRdbException("Duplicate hash fields detected");
            }
        }
        return hash;
    }

    /** rdbLoadDoubleValue - 길이 1바이트 + 10진수, 253 NaN·254 +inf·255 -inf */
    private double stringDouble() {
        int len = u8();
        return switch (len) {
            case 255 -> Double.NEGATIVE_INFINITY;
            case 254 -> Double.POSITIVE_INFINITY;
            case 253 -> Double.NaN;
            default -> Double.parseDouble(new String(bytes(len), StandardCharsets.US_ASCII));
        };
    }

    /** rdbGenericLoadStringObject - 정수 인코딩은 10진수 바이트로 */
    private byte[] string() {
        int first = u8();
        if ((first & 0xC0) >> 6 != RdbFormat.ENCVAL) {
            pos--;
            return bytes(toInt(length()));
        }
        int encoding = first & 0x3F;
        if (encoding == RdbFormat.ENC_LZF) {
            return lzfString();
        }
        long value = switch (encoding) {
            case RdbFormat.ENC_INT8 -> (byte) littleEndian(1);
            case RdbFormat.ENC_INT16 -> (short) littleEndian(2);
            case RdbFormat.ENC_INT32 -> (int) littleEndian(4);
            default -> throw new CorruptRdbException("Unknown RDB integer encoding type " + encoding);
        };
        return Long.toString(value).getBytes(StandardCharsets.US_ASCII);
    }

    /** rdbLoadLzfStringObject - [압축 길이][원래 길이][압축 데이터] */
    private byte[] lzfString() {
        int compressed = toInt(length());
        int original = toInt(length());
        byte[] blob = bytes(compressed);
        try {
            return Lzf.decompress(blob, original);
        } catch (IllegalArgumentException e) {
            throw new CorruptRdbException(e.getMessage());
        }
    }

    /** rdbLoadLen - 특수 인코딩 표시(11)도 하위 6비트를 길이로 (isencoded 없이 부른 경우와 동일) */
    private long length() {
        int first = u8();
        int type = (first & 0xC0) >> 6;
        if (type == RdbFormat.ENCVAL || type == RdbFormat.LEN_6BIT) {
            return first & 0x3F;
        }
        if (type == RdbFormat.LEN_14BIT) {
            return (first & 0x3F) << 8 | u8();
        }
        if (first == RdbFormat.LEN_32BIT) {
            return bigEndian(4);
        }
        if (first == RdbFormat.LEN_64BIT) {
            return bigEndian(8);
        }
        throw new CorruptRdbException("Unknown length encoding " + type + " in rdbLoadLen()");
    }

    private int u8() {
        need(1);
        return data[pos++] & 0xFF;
    }

    private byte[] bytes(int n) {
        need(n);
        byte[] out = Arrays.copyOfRange(data, pos, pos + n);
        pos += n;
        return out;
    }

    private long littleEndian(int n) {
        need(n);
        long v = 0;
        for (int i = 0; i < n; i++) {
            v |= (long) (data[pos + i] & 0xFF) << (8 * i);
        }
        pos += n;
        return v;
    }

    private long bigEndian(int n) {
        need(n);
        long v = 0;
        for (int i = 0; i < n; i++) {
            v = v << 8 | (data[pos + i] & 0xFF);
        }
        pos += n;
        return v;
    }

    private void need(int n) {
        if (n < 0 || n > data.length - pos) {
            throw new CorruptRdbException("Unexpected EOF reading RDB file");
        }
    }

    private static int toInt(long length) {
        if (length < 0 || length > Integer.MAX_VALUE - 8) {
            throw new CorruptRdbException("길이 " + Long.toUnsignedString(length) + " - 자바 배열 한도 초과");
        }
        return (int) length;
    }

    /** atoi - 숫자 아닌 곳에서 멈춤 */
    private static int atoi(byte[] text, int from) {
        int value = 0;
        for (int i = from; i < text.length && text[i] >= '0' && text[i] <= '9'; i++) {
            value = value * 10 + (text[i] - '0');
        }
        return value;
    }
}
