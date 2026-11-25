package glassredis.store.encoding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실수 출력 - 기대값은 Redis 7.2.4 컨테이너의 ZADD z 값 m → ZSCORE z m 출력 그대로
 * 정수로 떨어지는 값은 listpack 에 정수로 저장된 뒤 읽힌 값 (d2string 의 double2ll 분기와 같음)
 */
class DoublesTest {

    @Test
    @DisplayName("Grisu2 는 최단 자릿수가 아닐 때가 있음 - 1e23, 8.41e21 은 긴 정수 표기")
    void grisuIsNotAlwaysShortest() {
        assertFormat("99999999999999990000000", 1e23);
        assertFormat("8409999999999999000000", 8.41e21);
        // 최단 자릿수 왕복 알고리즘이라면 "1e+23", "8.41e+21"
    }

    @Test
    @DisplayName("소수점·지수 표기 선택 - fpconv emit_digits 규칙")
    void notation() {
        assertFormat("0.1", 0.1);
        assertFormat("0.30000000000000004", 0.30000000000000004);
        assertFormat("3.14159", 3.14159);
        assertFormat("-2.5", -2.5);
        assertFormat("100.5", 100.5);
        assertFormat("1234567.125", 1234567.125);
        assertFormat("123456789.123", 123456789.123);
        assertFormat("1000000000000000.3", 1000000000000000.2);
        assertFormat("0.3333333333333333", 1.0 / 3);
        assertFormat("0.6666666666666666", 2.0 / 3);
        assertFormat("0.001", 0.001);
        assertFormat("0.000001", 0.000001);
        assertFormat("0.00001", 1e-5);
        assertFormat("0.000025", 2.5e-5);
        assertFormat("1e-7", 1e-7);
        assertFormat("5e-7", 5e-7);
        assertFormat("1.23e-18", 1.23e-18);
    }

    @Test
    @DisplayName("큰 값 - 2^62 이하 정수는 정수 표기, 넘으면 fpconv 의 정수·지수 표기")
    void largeValues() {
        assertFormat("10000000000000000", 1e16);
        assertFormat("15000000000000000", 1.5e16);
        assertFormat("9007199254740992", 9007199254740993.0);
        assertFormat("4611686018427387904", 4611686018427387904.0);
        assertFormat("9223372036854776000", 9223372036854775807.0);
        assertFormat("12345678901234567000", 12345678901234567890.0);
        assertFormat("1e+19", 1e19);
        assertFormat("1e+21", 1e21);
        assertFormat("1e+22", 1e22);
        assertFormat("1e+100", 1e100);
        assertFormat("1.5e+300", 1.5e300);
        assertFormat("1.7976931348623157e+308", Double.MAX_VALUE);
        assertFormat("-1.7976931348623157e+308", -Double.MAX_VALUE);
    }

    @Test
    @DisplayName("작은 값 - 정규·비정규 경계")
    void tinyValues() {
        assertFormat("2.2250738585072014e-308", Double.MIN_NORMAL);
        assertFormat("1.7e-308", 1.7e-308);
        assertFormat("4.35e-322", 4.35e-322);
        assertFormat("1e-320", 1e-320);
        assertFormat("5e-324", Double.MIN_VALUE);
    }

    @Test
    @DisplayName("특수값 - d2string 규칙: nan, inf, -inf, 부호 있는 0")
    void specialValues() {
        assertFormat("inf", Double.POSITIVE_INFINITY);
        assertFormat("-inf", Double.NEGATIVE_INFINITY);
        assertFormat("nan", Double.NaN);
        assertFormat("0", 0.0);
        // skiplist 인코딩 ZSCORE 출력 - 1/value < 0 이면 "-0"
        assertFormat("-0", -0.0);
    }

    @Test
    @DisplayName("double2ll - 절댓값 2^62 이하 정수만 정수 취급, -0 도 정수 0")
    void double2ll() {
        assertTrue(Doubles.isSmallInteger(0x1p62));
        assertTrue(Doubles.isSmallInteger(-0x1p62));
        assertFalse(Doubles.isSmallInteger(0x1p62 + 1024));
        assertTrue(Doubles.isSmallInteger(-0.0));
        assertFalse(Doubles.isSmallInteger(0.5));
        assertFalse(Doubles.isSmallInteger(Double.NaN));
        assertFalse(Doubles.isSmallInteger(Double.POSITIVE_INFINITY));
    }

    @Test
    @DisplayName("출력을 다시 읽으면 같은 값 - zzlStrtod 왕복")
    void roundTrip() {
        double[] values = {1e23, 8.41e21, 0.1, 1.0 / 3, Double.MIN_VALUE, Double.MAX_VALUE, -1.5e-300,
                123456.789e10, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (double value : values) {
            assertEquals(value, Doubles.parse(Doubles.format(value)), "왕복 실패 " + value);
        }
    }

    private static void assertFormat(String expected, double value) {
        assertEquals(expected, new String(Doubles.format(value), StandardCharsets.US_ASCII), "입력 " + value);
    }
}
