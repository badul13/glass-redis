package glassredis.store.encoding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ListpackTest {

    @Test
    @DisplayName("빈 listpack - 헤더 6바이트와 끝 표시 1바이트")
    void empty() {
        Listpack lp = new Listpack();

        assertEquals(7, lp.bytes());
        assertEquals(0, lp.length());
        assertArrayEquals(new byte[] {7, 0, 0, 0, 0, 0, (byte) 0xFF}, lp.rawBytes());
    }

    @Test
    @DisplayName("원소 하나의 바이트 배치: 인코딩 + 데이터 + backlen")
    void entryLayout() {
        Listpack lp = new Listpack();
        lp.append(bytes("hi"));   // 6비트 문자열: 0x82 'h' 'i' + backlen 3
        lp.append(bytes("100"));  // 7비트 정수: 0x64 + backlen 1
        lp.append(bytes("-1"));   // 13비트 정수: 0xDF 0xFF + backlen 2

        assertArrayEquals(new byte[] {
                16, 0, 0, 0, 3, 0,
                (byte) 0x82, 'h', 'i', 3,
                0x64, 1,
                (byte) 0xDF, (byte) 0xFF, 2,
                (byte) 0xFF,
        }, lp.rawBytes());
    }

    @Test
    @DisplayName("정수로 되돌릴 수 있는 문자열만 정수로 저장, 007 같은 건 문자열 유지")
    void integerDetection() {
        assertEquals(1, Listpack.encode(bytes("5")).length);
        assertEquals(2, Listpack.encode(bytes("4095")).length);
        assertEquals(3, Listpack.encode(bytes("32767")).length);
        assertEquals(4, Listpack.encode(bytes("8388607")).length);
        assertEquals(5, Listpack.encode(bytes("2147483647")).length);
        assertEquals(9, Listpack.encode(bytes("9223372036854775807")).length);
        assertEquals(1 + 3, Listpack.encode(bytes("007")).length);
        assertEquals(1 + 2, Listpack.encode(bytes("+1")).length);
        assertEquals(1 + 19, Listpack.encode(bytes("9223372036854775808")).length);
    }

    @Test
    @DisplayName("문자열 길이별 6비트, 12비트, 32비트 길이 머리")
    void stringHeaders() {
        assertEquals(1 + 63, Listpack.encode(new byte[63]).length);
        assertEquals(2 + 64, Listpack.encode(new byte[64]).length);
        assertEquals(2 + 4095, Listpack.encode(new byte[4095]).length);
        assertEquals(5 + 4096, Listpack.encode(new byte[4096]).length);
    }

    @Test
    @DisplayName("backlen - 7비트씩 끊어 적어 뒤에서부터 읽기 가능")
    void backlenAllowsWalkingBackwards() {
        Listpack lp = new Listpack();
        lp.append(new byte[200]);  // 원소 길이 202 → backlen 2바이트
        lp.append(bytes("tail"));

        int last = lp.last();
        assertArrayEquals(bytes("tail"), lp.get(last));
        assertEquals(200, lp.get(lp.prev(last)).length);
        assertEquals(-1, lp.prev(lp.first()));
    }

    @Test
    @DisplayName("폭별 음수 정수 복원")
    void negativeIntegers() {
        long[] values = {-1, -4096, -4097, -32768, -32769, -8388608, -8388609, -2147483648L, -2147483649L,
                Long.MIN_VALUE};
        Listpack lp = new Listpack();
        for (long v : values) {
            lp.append(bytes(Long.toString(v)));
        }
        int p = lp.first();
        for (long v : values) {
            assertTrue(lp.isInteger(p));
            assertEquals(v, lp.integer(p));
            p = lp.next(p);
        }
    }

    @Test
    @DisplayName("seek - 음수는 뒤에서부터, 범위 밖이면 -1")
    void seek() {
        Listpack lp = listOf("a", "b", "c", "d");

        assertArrayEquals(bytes("a"), lp.get(lp.seek(0)));
        assertArrayEquals(bytes("d"), lp.get(lp.seek(-1)));
        assertArrayEquals(bytes("c"), lp.get(lp.seek(2)));
        assertEquals(-1, lp.seek(4));
        assertEquals(-1, lp.seek(-5));
    }

    @Test
    @DisplayName("find 는 skip 만큼 건너뛰며 비교 - Hash 는 필드만 대상")
    void findWithSkip() {
        Listpack lp = listOf("f1", "v1", "f2", "v1");

        assertEquals(lp.seek(1), lp.find(lp.first(), bytes("v1"), 0));
        assertEquals(-1, lp.find(lp.first(), bytes("v1"), 1));
        assertEquals(lp.seek(2), lp.find(lp.first(), bytes("f2"), 1));
    }

    @Test
    @DisplayName("정수 원소는 상대도 정수로 해석해 비교")
    void integerEquality() {
        Listpack lp = listOf("12");

        assertTrue(lp.equalsAt(lp.first(), bytes("12")));
        assertFalse(lp.equalsAt(lp.first(), bytes("012")));
    }

    @Test
    @DisplayName("deleteRange - 끝까지 지우면 EOF 를 앞으로 이동")
    void deleteRange() {
        Listpack lp = listOf("a", "b", "c", "d", "e");

        lp.deleteRange(1, 2);
        assertEquals(List.of("a", "d", "e"), strings(lp));

        lp.deleteRange(-2, 10);
        assertEquals(List.of("a"), strings(lp));
        assertEquals(7 + 3, lp.bytes()); // "a" 는 인코딩 1 + 데이터 1 + backlen 1
    }

    /** 헤더의 바이트 수와 원소 수도 매번 확인 */
    @Test
    @DisplayName("무작위 삽입·삭제·교체 결과가 ArrayList 와 동일")
    void matchesReference() {
        SplittableRandom random = new SplittableRandom(7);
        Listpack lp = new Listpack();
        List<String> reference = new ArrayList<>();
        for (int step = 0; step < 3000; step++) {
            String value = random.nextBoolean()
                    ? Long.toString(random.nextLong(-100_000, 100_000))
                    : "s".repeat(random.nextInt(0, 150));
            int op = random.nextInt(4);
            if (op == 0 || reference.isEmpty()) {
                lp.append(bytes(value));
                reference.add(value);
            } else if (op == 1) {
                int index = random.nextInt(reference.size());
                lp.insert(bytes(value), lp.seek(index), Listpack.Where.BEFORE);
                reference.add(index, value);
            } else if (op == 2) {
                int index = random.nextInt(reference.size());
                lp.delete(lp.seek(index));
                reference.remove(index);
            } else {
                int index = random.nextInt(reference.size());
                lp.replace(lp.seek(index), bytes(value));
                reference.set(index, value);
            }
        }
        assertEquals(reference, strings(lp));
        assertEquals(reference.size(), lp.length());
        assertEquals(lp.rawBytes().length, lp.bytes());
        int expectedBytes = 7;
        for (String value : reference) {
            expectedBytes += Listpack.entrySize(bytes(value));
        }
        assertEquals(expectedBytes, lp.bytes());
    }

    @Test
    @DisplayName("원소 65535개부터 헤더 원소 수는 모름(65535) - length 는 끝까지 세고, 다시 줄면 헤더 복원 (lpLength)")
    void numElementsUnknown() {
        Listpack lp = Listpack.fromBytes(smallIntegers(65534));
        assertEquals(65534, headerCount(lp));

        lp.append(bytes("1"));
        assertEquals(Listpack.NUMELE_UNKNOWN, headerCount(lp), "65535개째에서 모름");
        lp.append(bytes("2"));
        assertEquals(Listpack.NUMELE_UNKNOWN, headerCount(lp));
        assertEquals(65536, lp.length(), "모름이면 걸어서 계수");
        assertEquals(Listpack.NUMELE_UNKNOWN, headerCount(lp), "65535 이상이면 헤더 그대로");

        // 모름 상태의 seek - 음수는 뒤에서 걷기
        assertEquals("2", new String(lp.get(lp.seek(-1)), StandardCharsets.US_ASCII));
        assertEquals("1", new String(lp.get(lp.seek(-2)), StandardCharsets.US_ASCII));
        assertEquals(6, lp.seek(0));

        lp.delete(lp.seek(-1));
        lp.delete(lp.seek(-1));
        assertEquals(Listpack.NUMELE_UNKNOWN, headerCount(lp), "삭제만으로는 헤더 갱신 없음");
        assertEquals(65534, lp.length());
        assertEquals(65534, headerCount(lp), "계수 결과가 범위 안이면 헤더에 기록");
    }

    @Test
    @DisplayName("backlen 3·4바이트 - 16KB·2MB 문자열도 뒤에서 걸어 앞 원소로 이동")
    void longBacklensWalkBackwards() {
        byte[] mid = new byte[20_000];      // 32비트 길이 머리 5 + 20000 → backlen 3바이트
        byte[] big = new byte[2_200_000];   // 5 + 2.2MB → backlen 4바이트
        Arrays.fill(mid, (byte) 'm');
        Arrays.fill(big, (byte) 'b');
        Listpack lp = new Listpack();
        lp.append(bytes("head"));
        lp.append(mid);
        lp.append(big);
        lp.append(bytes("tail"));

        assertEquals(7 + 6 + (5 + 20_000 + 3) + (5 + 2_200_000 + 4) + 6, lp.bytes());
        int p = lp.last();
        assertEquals("tail", new String(lp.get(p), StandardCharsets.US_ASCII));
        p = lp.prev(p);
        assertArrayEquals(big, lp.get(p));
        p = lp.prev(p);
        assertArrayEquals(mid, lp.get(p));
        p = lp.prev(p);
        assertEquals("head", new String(lp.get(p), StandardCharsets.US_ASCII));
        assertEquals(-1, lp.prev(p));
    }

    @Test
    @DisplayName("backlen 경계 - 127/128, 16382/16383, 2097150/2097151, 268435454/268435455 (lpEncodeBacklen)")
    void backlenBoundaries() {
        assertEquals(1, Listpack.encodeBacklen(null, 0, 127));
        assertEquals(2, Listpack.encodeBacklen(null, 0, 128));
        assertEquals(2, Listpack.encodeBacklen(null, 0, 16382));
        assertEquals(3, Listpack.encodeBacklen(null, 0, 16383));
        assertEquals(3, Listpack.encodeBacklen(null, 0, 2097150));
        assertEquals(4, Listpack.encodeBacklen(null, 0, 2097151));
        assertEquals(4, Listpack.encodeBacklen(null, 0, 268435454));
        assertEquals(5, Listpack.encodeBacklen(null, 0, 268435455));

        // 5바이트 - 상위 7비트부터, 첫 바이트 외에는 최상위 비트 1
        byte[] buf = new byte[5];
        Listpack.encodeBacklen(buf, 0, 300_000_000L);
        assertArrayEquals(new byte[] {(byte) (300_000_000L >> 28), (byte) (((300_000_000L >> 21) & 127) | 128),
                (byte) (((300_000_000L >> 14) & 127) | 128), (byte) (((300_000_000L >> 7) & 127) | 128),
                (byte) ((300_000_000L & 127) | 128)}, buf);
    }

    @Test
    @DisplayName("정수 폭 경계 양쪽 - 7비트·13비트·16·24·32·64비트 (lpEncodeIntegerGetType)")
    void integerWidthBoundaries() {
        long[][] cases = {
                {0, 0x00}, {127, 0x7F},
                {128, 0xC0}, {-1, 0xC0}, {4095, 0xC0}, {-4096, 0xC0},
                {4096, 0xF1}, {-4097, 0xF1}, {32767, 0xF1}, {-32768, 0xF1},
                {32768, 0xF2}, {-32769, 0xF2}, {8388607, 0xF2}, {-8388608, 0xF2},
                {8388608, 0xF3}, {-8388609, 0xF3}, {2147483647L, 0xF3}, {-2147483648L, 0xF3},
                {2147483648L, 0xF4}, {-2147483649L, 0xF4}, {Long.MAX_VALUE, 0xF4}, {Long.MIN_VALUE, 0xF4},
        };
        for (long[] c : cases) {
            Listpack lp = listOf(Long.toString(c[0]));
            int p = lp.first();
            int head = lp.rawBytes()[p] & 0xFF;
            if (c[1] == 0x00) {
                assertEquals(c[0], head, "7비트 " + c[0]);
            } else if (c[1] == 0xC0) {
                assertEquals(0xC0, head & 0xE0, "13비트 " + c[0]);
            } else {
                assertEquals(c[1], head, "폭 " + c[0]);
            }
            assertTrue(lp.isInteger(p));
            assertEquals(c[0], lp.integer(p), "복원 " + c[0]);
        }
    }

    @Test
    @DisplayName("insert AFTER - 지정 원소 뒤에 넣고 넣은 위치 반환")
    void insertAfter() {
        Listpack lp = listOf("a", "c");

        int p = lp.insert(bytes("b"), lp.first(), Listpack.Where.AFTER);
        assertEquals("b", new String(lp.get(p), StandardCharsets.US_ASCII));
        lp.insert(bytes("d"), lp.last(), Listpack.Where.AFTER);

        assertEquals(List.of("a", "b", "c", "d"), strings(lp));
        assertEquals(4, lp.length());
    }

    @Test
    @DisplayName("deleteRangeWithEntry - 지정 위치부터 n개, 끝을 넘으면 끝까지 지우고 -1")
    void deleteRangeWithEntry() {
        Listpack lp = listOf("a", "b", "c", "d", "e");

        int next = lp.deleteRangeWithEntry(lp.seek(1), 2);
        assertEquals("d", new String(lp.get(next), StandardCharsets.US_ASCII));
        assertEquals(List.of("a", "d", "e"), strings(lp));

        assertEquals(-1, lp.deleteRangeWithEntry(lp.seek(1), 10));
        assertEquals(List.of("a"), strings(lp));
        assertEquals(1, lp.length());
        assertEquals(lp.rawBytes().length, lp.bytes());
    }

    @Test
    @DisplayName("fromBytes 얕은 검사 실패 - 총 바이트 불일치, 끝 0xFF 없음, 헤더보다 짧음")
    void fromBytesRejectsBrokenHeaders() {
        byte[] good = listOf("a").rawBytes().clone();
        assertEquals(1, Listpack.fromBytes(good.clone()).length());

        byte[] wrongTotal = good.clone();
        wrongTotal[0]++;
        byte[] noEof = good.clone();
        noEof[noEof.length - 1] = 0;
        for (byte[] broken : new byte[][] {wrongTotal, noEof, new byte[6]}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> Listpack.fromBytes(broken));
            assertEquals("Listpack integrity check failed.", e.getMessage());
        }
    }

    /** 원소 수 헤더 그대로 - 모름이면 65535 */
    private static int headerCount(Listpack lp) {
        byte[] raw = lp.rawBytes();
        return (raw[4] & 0xFF) | (raw[5] & 0xFF) << 8;
    }

    /** 1바이트 정수 원소(0) n개짜리 listpack 바이트 - 헤더 원소 수는 n (65535 이상이면 모름) */
    private static byte[] smallIntegers(int n) {
        int total = Listpack.HEADER_SIZE + 2 * n + 1;
        byte[] raw = new byte[total];
        raw[0] = (byte) total;
        raw[1] = (byte) (total >>> 8);
        raw[2] = (byte) (total >>> 16);
        raw[3] = (byte) (total >>> 24);
        int count = Math.min(n, Listpack.NUMELE_UNKNOWN);
        raw[4] = (byte) count;
        raw[5] = (byte) (count >>> 8);
        for (int i = 0; i < n; i++) {
            raw[Listpack.HEADER_SIZE + 2 * i] = 0x00;     // 7비트 정수 0
            raw[Listpack.HEADER_SIZE + 2 * i + 1] = 0x01; // backlen 1
        }
        raw[total - 1] = (byte) 0xFF;
        return raw;
    }

    private static Listpack listOf(String... values) {
        Listpack lp = new Listpack();
        for (String value : values) {
            lp.append(bytes(value));
        }
        return lp;
    }

    private static List<String> strings(Listpack lp) {
        List<String> out = new ArrayList<>();
        for (int p = lp.first(); p != -1; p = lp.next(p)) {
            out.add(new String(lp.get(p), StandardCharsets.UTF_8));
        }
        return out;
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
