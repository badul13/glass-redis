package glassredis.persistence;

import glassredis.command.CommandRegistry;
import glassredis.command.Context;
import glassredis.store.Entry;
import glassredis.store.HashValue;
import glassredis.store.Key;
import glassredis.store.Keyspace;
import glassredis.store.ListValue;
import glassredis.store.ManualClock;
import glassredis.store.SetValue;
import glassredis.store.SortedSetValue;
import glassredis.store.Value;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Redis 7.2.4 가 쓴 dump.rdb(16진수)와 직접 만든 바이트로 로드 규칙 확인 */
class RdbLoaderTest {

    private static final long NOW = 1_000_000_000L;

    private final ManualClock clock = new ManualClock(NOW);
    private final Keyspace source = new Keyspace(clock);
    private final CommandRegistry registry = CommandRegistry.withBuiltins();
    private final Context ctx = new Context(source);

    private Keyspace loaded;

    private void run(String... argv) {
        List<byte[]> all = new ArrayList<>();
        for (String arg : argv) {
            all.add(arg.getBytes(StandardCharsets.UTF_8));
        }
        ctx.propagation().begin(all);
        registry.find(argv[0].toUpperCase(Locale.ROOT)).execute(ctx, all.subList(1, all.size()));
        ctx.propagation().finish();
    }

    private RdbLoader.Result load(byte[] rdb) {
        loaded = new Keyspace(clock);
        return RdbLoader.load(rdb, loaded);
    }

    private RdbLoader.Result loadHex(String hex) {
        return load(HexFormat.of().parseHex(hex));
    }

    private Value value(String key) {
        Entry entry = loaded.peek(Key.of(key));
        return entry == null ? null : entry.value();
    }

    @Test
    @DisplayName("Redis 가 쓴 작은 인코딩 - listpack·intset 그대로 받음")
    void redisSmallEncodings() {
        RdbLoader.Result result = loadHex("524544495330303131fa0972656469732d76657205372e322e34fa0a72656469732d62697473c040fa056374696d65c25736d76afa08757365642d6d656dc2a86f1000fa08616f662d62617365c000fe00fb01000b027369180400000004000000fdffffff010000000500000070110100ff15553d74ebd57cb9");
        assertEquals(RdbLoader.Status.OK, result.status(), result.error());
        assertEquals("7.2.4", result.createdBy());
        assertEquals("intset", value("si").encoding());
        assertEquals(List.of("-3", "1", "5", "70000"), members(value("si")));

        loadHex("524544495330303131fa0972656469732d76657205372e322e34fa0a72656469732d62697473c040fa056374696d65c25736d76afa08757365642d6d656dc228d01000fa08616f662d62617365c000fe00fb010011027a6c22220000000800816302dffd02816102010181620283322e350481640283696e6604ffff2fe42fc00a99ecb8");
        assertEquals("listpack", value("zl").encoding());
        assertEquals("c=-3.0 a=1.0 b=2.5 d=Infinity", scores(value("zl")));

        loadHex("524544495330303131fa0972656469732d76657205372e322e34fa0a72656469732d62697473c040fa056374696d65c25736d76afa08757365642d6d656dc2380f1000fa08616f662d62617365c000fe00fb010012016c0102151500000005008161028162020101dffe02c12c02ffff49bd61a34e052d4c");
        assertEquals("listpack", value("l").encoding());
        assertEquals(List.of("a", "b", "1", "-2", "300"), items(value("l")));
    }

    @Test
    @DisplayName("Redis 가 쓴 문자열 - 만료와 LZF 압축 풀기")
    void redisStrings() {
        loadHex("524544495330303131fa0972656469732d76657205372e322e34fa0a72656469732d62697473c040fa056374696d65c24f36d76afa08757365642d6d656dc218af0f00fa08616f662d62617365c000fe00fb0101fc00d8c32cbb0300000001730568656c6c6fff4a8bc1574b2c2516");
        assertEquals(4102444800000L, loaded.peek(Key.of("s")).expireAtMillis());
        assertEquals("embstr", value("s").encoding());

        loadHex("524544495330303131fa0972656469732d76657205372e322e34fa0a72656469732d62697473c040fa056374696d65c24f36d76afa08757365642d6d656dc200af0f00fa08616f662d62617365c000fe00fb01000003726177c3343f1f74686520717569636b2062726f776e20666f78206a756d7073206f7665722074201e076c617a7920646f67600ce0042b016f78ffb6d0af9f9ad05f67");
        assertEquals("the quick brown fox jumps over the lazy dog the quick brown fox", text(value("raw")));
        assertEquals("raw", value("raw").encoding());

        loadHex("524544495330303131fa0972656469732d76657205372e322e34fa0a72656469732d62697473c040fa056374696d65c24f36d76afa08757365642d6d656dc2d0ae0f00fa08616f662d62617365c000fe00fb010000036e3332c2409c0000ffe4ead710e1128700");
        assertEquals("40000", text(value("n32")));
        assertEquals("int", value("n32").encoding());
    }

    @Test
    @DisplayName("Redis 가 쓴 hashtable 셋·해시 - 원소 하나씩, 긴 원소는 LZF")
    void redisHashtables() {
        loadHex("524544495330303131fa0972656469732d76657205372e322e34fa0a72656469732d62697473c040fa056374696d65c27036d76afa08757365642d6d656dc240311100fa08616f662d62617365c000fe00fb010002027368040163c3094046016464e0390001646401620161ff977544b07aa35355");
        assertEquals("hashtable", value("sh").encoding());
        assertEquals(List.of("a", "b", "c", "d".repeat(70)), members(value("sh")));

        loadHex("524544495330303131fa0972656469732d76657205372e322e34fa0a72656469732d62697473c040fa056374696d65c27036d76afa08757365642d6d656dc270311100fa08616f662d62617365c000fe00fb01000402686802026631027631046c6f6e67c3094050017878e04300017878fffaa334186f8280ee");
        assertEquals("hashtable", value("hh").encoding());
        assertEquals(Map.of("f1", "v1", "long", "x".repeat(80)), fields(value("hh")));
    }

    @Test
    @DisplayName("왕복 - 큰 리스트·skiplist·hashtable 도 내용과 인코딩 그대로")
    void roundTrip() {
        for (int i = 0; i < 600; i++) {
            run("RPUSH", "q", "element-number-" + i);
            run("SADD", "ints", Integer.toString(i * 7));
            run("HSET", "h", "f" + i, "v" + i);
        }
        for (int i = 0; i < 200; i++) {
            run("ZADD", "zs", i + ".25", "m" + i);
            run("SADD", "words", "w" + i);
        }
        run("SET", "big", "x".repeat(5000));

        RdbLoader.Result result = load(RdbWriter.write(source, 1, 1));
        assertEquals(RdbLoader.Status.OK, result.status(), result.error());
        assertEquals(source.size(), result.keys());
        source.forEach((key, entry) -> {
            Value original = entry.value();
            Value copy = loaded.peek(key).value();
            assertEquals(original.encoding(), copy.encoding(), key.toString());
            assertEquals(original.size(), copy.size(), key.toString());
        });
        assertEquals(items(source.peek(Key.of("q")).value()), items(value("q")));
        assertEquals(members(source.peek(Key.of("ints")).value()), members(value("ints")));
        assertEquals(members(source.peek(Key.of("words")).value()), members(value("words")));
        assertEquals(scores(source.peek(Key.of("zs")).value()), scores(value("zs")));
        assertEquals(fields(source.peek(Key.of("h")).value()), fields(value("h")));
        assertEquals("x".repeat(5000), text(value("big")));
    }

    @Test
    @DisplayName("지난 만료 키는 버림 - 같은 ms 는 살아 있음")
    void dropsExpiredKeys() {
        run("SET", "gone", "1", "PXAT", Long.toString(NOW + 10));
        run("SET", "edge", "1", "PXAT", Long.toString(NOW + 100));
        run("SET", "kept", "1", "PXAT", Long.toString(NOW + 1000));
        byte[] rdb = RdbWriter.write(source, 1, 1);
        clock.advanceMillis(100);

        RdbLoader.Result result = load(rdb);
        assertEquals(2, result.keys());
        assertEquals(1, result.expired());
        assertNull(value("gone"));
        assertEquals(NOW + 100, loaded.peek(Key.of("edge")).expireAtMillis());
    }

    @Test
    @DisplayName("체크섬 - 틀리면 실패, 0 이면 검사 생략")
    void checksum() {
        run("SET", "k", "v");
        byte[] rdb = RdbWriter.write(source, 1, 1);
        rdb[rdb.length - 1] ^= 1;
        RdbLoader.Result result = load(rdb);
        assertEquals(RdbLoader.Status.FAILED, result.status());
        assertTrue(result.error().startsWith("Wrong RDB checksum"), result.error());

        for (int i = 1; i <= 8; i++) {
            rdb[rdb.length - i] = 0;
        }
        assertEquals(RdbLoader.Status.OK, load(rdb).status());
    }

    @Test
    @DisplayName("잘린 파일·모르는 버전·같은 키 두 번은 실패")
    void failures() {
        run("SET", "k", "v");
        byte[] rdb = RdbWriter.write(source, 1, 1);
        byte[] cut = java.util.Arrays.copyOf(rdb, rdb.length - 12);
        assertEquals("Unexpected EOF reading RDB file", load(cut).error());

        byte[] v12 = rdb.clone();
        v12[7] = '1';
        v12[8] = '2';
        assertEquals("Can't handle RDB format version 12", load(v12).error());

        Crafted twice = new Crafted();
        twice.entry(RdbFormat.TYPE_STRING, "k").string("1");
        twice.entry(RdbFormat.TYPE_STRING, "k").string("2");
        assertEquals("RDB has duplicated key 'k' in DB 0", load(twice.finish()).error());
    }

    @Test
    @DisplayName("원소 나열 형식은 한도 안이면 작은 인코딩으로 - 정수만이면 intset")
    void plainTypesShrink() {
        Crafted rdb = new Crafted();
        rdb.entry(RdbFormat.TYPE_SET, "ints").length(2).string("1").string("2");
        rdb.entry(RdbFormat.TYPE_SET, "mixed").length(2).string("1").string("x");
        rdb.entry(RdbFormat.TYPE_ZSET_2, "z").length(2).string("b").score(2).string("a").score(1);
        rdb.entry(RdbFormat.TYPE_HASH, "h").length(1).string("f").string("v");
        rdb.entry(RdbFormat.TYPE_HASH, "wide").length(2).string("f").string("v").string("g").string("y".repeat(65));
        rdb.entry(RdbFormat.TYPE_SET, "empty").length(0);
        RdbLoader.Result result = load(rdb.finish());

        assertEquals(RdbLoader.Status.OK, result.status(), result.error());
        assertEquals("intset", value("ints").encoding());
        assertEquals("listpack", value("mixed").encoding());
        assertEquals("listpack", value("z").encoding());
        assertEquals("a=1.0 b=2.0", scores(value("z")));
        assertEquals("listpack", value("h").encoding());
        assertEquals("hashtable", value("wide").encoding());
        assertEquals(1, result.emptySkipped());
        assertNull(value("empty"));
    }

    // --- 값 꺼내기 ---

    private static String text(Value value) {
        return new String(((glassredis.store.StringValue) value).bytes(), StandardCharsets.UTF_8);
    }

    private static List<String> items(Value value) {
        ListValue list = (ListValue) value;
        List<String> out = new ArrayList<>();
        list.range(0, list.length() - 1).forEach(b -> out.add(new String(b, StandardCharsets.UTF_8)));
        return out;
    }

    /** 순서 무관 비교용 - 정렬 */
    private static List<String> members(Value value) {
        List<String> out = new ArrayList<>();
        ((SetValue) value).forEach(b -> out.add(new String(b, StandardCharsets.UTF_8)));
        out.sort(null);
        return out;
    }

    private static String scores(Value value) {
        SortedSetValue zset = (SortedSetValue) value;
        List<String> out = new ArrayList<>();
        zset.forEachInRankRange(0, zset.size() - 1, false,
                (member, score) -> out.add(new String(member, StandardCharsets.UTF_8) + "=" + score));
        return String.join(" ", out);
    }

    private static Map<String, String> fields(Value value) {
        Map<String, String> out = new TreeMap<>();
        ((HashValue) value).forEach((f, v) -> out.put(new String(f, StandardCharsets.UTF_8),
                new String(v, StandardCharsets.UTF_8)));
        return out;
    }

    /** 손으로 만드는 RDB - 짧은 문자열만, 체크섬 0 (검사 생략) */
    private static final class Crafted {

        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Crafted() {
            out.writeBytes("REDIS0011".getBytes(StandardCharsets.US_ASCII));
        }

        Crafted entry(int type, String key) {
            out.write(type);
            return string(key);
        }

        Crafted length(int n) {
            out.write(n); // 6비트 길이
            return this;
        }

        Crafted string(String s) {
            byte[] bytes = s.getBytes(StandardCharsets.US_ASCII);
            if (bytes.length < 64) {
                out.write(bytes.length);
            } else {
                out.write(0x40 | bytes.length >> 8);
                out.write(bytes.length & 0xFF);
            }
            out.writeBytes(bytes);
            return this;
        }

        Crafted score(double score) {
            out.writeBytes(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(score).array());
            return this;
        }

        byte[] finish() {
            out.write(RdbFormat.OPCODE_EOF);
            out.writeBytes(new byte[8]);
            return out.toByteArray();
        }
    }
}
