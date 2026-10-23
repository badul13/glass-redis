package glassredis.persistence;

import glassredis.command.CommandRegistry;
import glassredis.command.Context;
import glassredis.store.Keyspace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 기대값은 Docker redis:7.2.4-alpine 에 같은 명령을 넣고 SAVE 한 dump.rdb
 * 키 하나씩 - 키가 여럿이면 순서가 해시 시드 따라 달라 비교 불가
 * ctime·used-mem 은 실행마다 다른 값이라 Redis 파일에서 읽어 같은 값으로 씀
 */
class RdbWriterTest {

    private final Keyspace keyspace = new Keyspace();
    private final CommandRegistry registry = CommandRegistry.withBuiltins();
    private final Context ctx = new Context(keyspace);

    private void run(String... argv) {
        List<byte[]> all = new ArrayList<>();
        for (String arg : argv) {
            all.add(arg.getBytes(StandardCharsets.UTF_8));
        }
        ctx.propagation().begin(all);
        registry.find(argv[0].toUpperCase(Locale.ROOT)).execute(ctx, all.subList(1, all.size()));
        ctx.propagation().finish();
    }

    /** Redis 파일과 같은 ctime·used-mem 으로 쓴 결과 */
    private byte[] writeLike(byte[] redis) {
        return RdbWriter.write(keyspace, auxInteger(redis, "ctime"), auxInteger(redis, "used-mem"), false);
    }

    private void assertSameAsRedis(String redisHex) {
        byte[] redis = HexFormat.of().parseHex(redisHex);
        assertEquals(redisHex, HexFormat.of().formatHex(writeLike(redis)));
    }

    @Test
    @DisplayName("정수 문자열 - 8비트·32비트는 특수 인코딩, 32비트 밖은 10진수 그대로")
    void integers() {
        run("SET", "n8", "123");
        assertSameAsRedis("524544495330303131fa0972656469732d76657205372e322e34fa0a72656469732d62697473c040fa056374696d65c24f36d76afa08757365642d6d656dc2e0ed0e00fa08616f662d62617365c000fe00fb010000026e38c07bff6fc1f9fb440faa54");
        run("DEL", "n8");
        run("SET", "n32", "40000");
        assertSameAsRedis("524544495330303131fa0972656469732d76657205372e322e34fa0a72656469732d62697473c040fa056374696d65c24f36d76afa08757365642d6d656dc2d0ae0f00fa08616f662d62617365c000fe00fb010000036e3332c2409c0000ffe4ead710e1128700");
        run("DEL", "n32");
        run("SET", "n64", "1099511627776");
        assertSameAsRedis("524544495330303131fa0972656469732d76657205372e322e34fa0a72656469732d62697473c040fa056374696d65c24f36d76afa08757365642d6d656dc2d0ae0f00fa08616f662d62617365c000fe00fb010000036e36340d31303939353131363237373736ffe9b18f6874d26995");
    }

    @Test
    @DisplayName("만료 - 키 앞에 FC + 8바이트 ms")
    void expire() {
        run("SET", "s", "hello", "PXAT", "4102444800000");
        assertSameAsRedis("524544495330303131fa0972656469732d76657205372e322e34fa0a72656469732d62697473c040fa056374696d65c24f36d76afa08757365642d6d656dc218af0f00fa08616f662d62617365c000fe00fb0101fc00d8c32cbb0300000001730568656c6c6fff4a8bc1574b2c2516");
    }

    @Test
    @DisplayName("20바이트 넘는 문자열은 LZF - 반복 많으면 크게 줄어듦")
    void lzf() {
        run("SET", "lzf", "a".repeat(100));
        assertSameAsRedis("524544495330303131fa0972656469732d76657205372e322e34fa0a72656469732d62697473c040fa056374696d65c24f36d76afa08757365642d6d656dc220af0f00fa08616f662d62617365c000fe00fb010000036c7a66c3094064016161e05700016161ffbd51d3fa0da90dc2");
        run("DEL", "lzf");
        run("SET", "raw", "the quick brown fox jumps over the lazy dog the quick brown fox");
        assertSameAsRedis("524544495330303131fa0972656469732d76657205372e322e34fa0a72656469732d62697473c040fa056374696d65c24f36d76afa08757365642d6d656dc200af0f00fa08616f662d62617365c000fe00fb01000003726177c3343f1f74686520717569636b2062726f776e20666f78206a756d7073206f7665722074201e076c617a7920646f67600ce0042b016f78ffb6d0af9f9ad05f67");
    }

    @Test
    @DisplayName("listpack 하나짜리 리스트 - 노드 하나인 quicklist(18)로")
    void smallList() {
        run("RPUSH", "l", "a", "b", "1", "-2", "300");
        assertSameAsRedis("524544495330303131fa0972656469732d76657205372e322e34fa0a72656469732d62697473c040fa056374696d65c25736d76afa08757365642d6d656dc2380f1000fa08616f662d62617365c000fe00fb010012016c0102151500000005008161028162020101dffe02c12c02ffff49bd61a34e052d4c");
    }

    @Test
    @DisplayName("quicklist - 8KB 노드마다 listpack 덩어리, 덩어리도 LZF")
    void quicklist() throws Exception {
        for (int i = 0; i < 600; i++) {
            run("RPUSH", "q", "element-number-" + i);
        }
        byte[] ours = RdbWriter.write(keyspace, 0x6ad73666L, 0x001160e8L, false);
        assertEquals(3174, ours.length);
        assertEquals("01c9da2537667a95ba6414d737a507e718fa360d4ee92ee324eff14457c7bf56", sha256(ours));
    }

    @Test
    @DisplayName("셋 - intset·listpack 은 메모리 속 바이트 그대로")
    void sets() {
        run("SADD", "si", "5", "-3", "70000", "1");
        assertSameAsRedis("524544495330303131fa0972656469732d76657205372e322e34fa0a72656469732d62697473c040fa056374696d65c25736d76afa08757365642d6d656dc2a86f1000fa08616f662d62617365c000fe00fb01000b027369180400000004000000fdffffff010000000500000070110100ff15553d74ebd57cb9");
        run("DEL", "si");
        run("SADD", "sl", "x", "1", "yy");
        assertSameAsRedis("524544495330303131fa0972656469732d76657205372e322e34fa0a72656469732d62697473c040fa056374696d65c25736d76afa08757365642d6d656dc2a06f1000fa08616f662d62617365c000fe00fb01001402736c10100000000300817802010182797903ffff431dd391305a76fb");
    }

    @Test
    @DisplayName("정렬 셋 listpack - 정수 점수는 정수 원소, inf 는 문자열")
    void smallSortedSet() {
        run("ZADD", "zl", "1", "a", "2.5", "b", "-3", "c", "inf", "d");
        assertSameAsRedis("524544495330303131fa0972656469732d76657205372e322e34fa0a72656469732d62697473c040fa056374696d65c25736d76afa08757365642d6d656dc228d01000fa08616f662d62617365c000fe00fb010011027a6c22220000000800816302dffd02816102010181620283322e350481640283696e6604ffff2fe42fc00a99ecb8");
    }

    @Test
    @DisplayName("정렬 셋 skiplist - 큰 점수부터 멤버 + 8바이트 double")
    void skiplist() throws Exception {
        for (int i = 0; i < 200; i++) {
            run("ZADD", "zs", i + ".25", "m" + i);
        }
        byte[] ours = RdbWriter.write(keyspace, 0x6ad73666L, 0x001178f0L, false);
        assertEquals(2589, ours.length);
        assertEquals("7b60dcff8b4299f97b955ab299c2c0f6f468e4128d85e535b873a11e0a1027a1", sha256(ours));
    }

    @Test
    @DisplayName("해시 listpack")
    void smallHash() {
        run("HSET", "hl", "f1", "v1", "f2", "2");
        assertSameAsRedis("524544495330303131fa0972656469732d76657205372e322e34fa0a72656469732d62697473c040fa056374696d65c25736d76afa08757365642d6d656dc288301100fa08616f662d62617365c000fe00fb01001002686c151500000004008266310382763103826632030201ffffbd231227869f1486");
    }

    @Test
    @DisplayName("AOF base (aof-use-rdb-preamble yes) - 같은 RDB 에 aof-base 만 1, 빈 데이터도 RDB")
    void aofBase() {
        // appendonly yes 로 처음 켰을 때의 appendonly.aof.1.base.rdb
        String empty = "524544495330303131fa0972656469732d76657205372e322e34fa0a72656469732d62697473c040fa056374696d65c265f3da6afa08757365642d6d656dc2d8370d00fa08616f662d62617365c001ff835994112912e545";
        byte[] redis = HexFormat.of().parseHex(empty);
        assertEquals(empty, HexFormat.of().formatHex(
                RdbWriter.write(keyspace, auxInteger(redis, "ctime"), auxInteger(redis, "used-mem"), true)));

        // SET n8 123 뒤 BGREWRITEAOF 의 appendonly.aof.2.base.rdb
        run("SET", "n8", "123");
        String one = "524544495330303131fa0972656469732d76657205372e322e34fa0a72656469732d62697473c040fa056374696d65c267f3da6afa08757365642d6d656dc260300e00fa08616f662d62617365c001fe00fb010000026e38c07bffd8d090adc943658b";
        redis = HexFormat.of().parseHex(one);
        assertEquals(one, HexFormat.of().formatHex(
                RdbWriter.write(keyspace, auxInteger(redis, "ctime"), auxInteger(redis, "used-mem"), true)));
    }

    @Test
    @DisplayName("빈 데이터 - SELECTDB 없이 보조 필드 뒤 바로 EOF")
    void empty() {
        byte[] rdb = RdbWriter.write(keyspace, 1, 1, false);
        String hex = HexFormat.of().formatHex(rdb);
        assertEquals("ff", hex.substring(hex.length() - 18, hex.length() - 16));
        assertEquals(-1, hex.indexOf("fe00fb"));
    }

    @Test
    @DisplayName("CRC64 - Redis crc64 시험값")
    void crc64() {
        byte[] text = "123456789".getBytes(StandardCharsets.US_ASCII);
        assertEquals(0xe9c6d914c4b8d9caL, Crc64.update(0, text, 0, text.length));
    }

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    /** AUX 키 뒤 값 - C0/C1/C2 정수 인코딩만 */
    private static long auxInteger(byte[] rdb, String key) {
        byte[] name = key.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i + name.length < rdb.length; i++) {
            if (rdb[i] == name.length && java.util.Arrays.equals(rdb, i + 1, i + 1 + name.length, name, 0, name.length)) {
                int p = i + 1 + name.length;
                int width = switch (rdb[p] & 0xFF) {
                    case 0xC0 -> 1;
                    case 0xC1 -> 2;
                    case 0xC2 -> 4;
                    default -> throw new IllegalArgumentException("정수 인코딩 아님");
                };
                long v = 0;
                for (int b = 0; b < width; b++) {
                    v |= (long) (rdb[p + 1 + b] & 0xFF) << (8 * b);
                }
                return v;
            }
        }
        throw new IllegalArgumentException(key + " 없음");
    }
}
