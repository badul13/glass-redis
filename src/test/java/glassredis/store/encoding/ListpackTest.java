package glassredis.store.encoding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    @DisplayName("toInt64 - string2ll 과 같은 표기만 허용")
    void toInt64() {
        assertEquals(Long.MIN_VALUE, Listpack.toInt64(bytes("-9223372036854775808")));
        assertNull(Listpack.toInt64(bytes("-9223372036854775809")));
        assertNull(Listpack.toInt64(bytes("-0")));
        assertNull(Listpack.toInt64(bytes("")));
        assertNull(Listpack.toInt64(bytes(" 1")));
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
