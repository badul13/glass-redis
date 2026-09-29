package glassredis.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.OptionalDouble;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NumbersTest {

    @Test
    @DisplayName("평범한 정수와 long 범위 양 끝 파싱")
    void parsesValidIntegers() {
        assertEquals(OptionalLong.of(0), parse("0"));
        assertEquals(OptionalLong.of(42), parse("42"));
        assertEquals(OptionalLong.of(-42), parse("-42"));
        assertEquals(OptionalLong.of(Long.MAX_VALUE), parse("9223372036854775807"));
        assertEquals(OptionalLong.of(Long.MIN_VALUE), parse("-9223372036854775808"));
    }

    @Test
    @DisplayName("Redis 가 정수로 보지 않는 표기는 거절")
    void rejectsNonCanonicalOrOutOfRange() {
        String[] rejected = {
                "", "-", "+5", "007", "-0", "1.5", " 1", "1 ", "12a",
                "9223372036854775808", "-9223372036854775809", "123456789012345678901",
        };
        for (String text : rejected) {
            assertTrue(parse(text).isEmpty(), "정수로 받아들이면 안 된다: \"" + text + "\"");
        }
    }

    @Test
    @DisplayName("점수는 strtod 방식으로 파싱 - inf 표기 허용, 공백·자바 접미사·NaN·범위 초과는 거절")
    void parsesScores() {
        assertEquals(OptionalDouble.of(1.5), parseScore("1.5"));
        assertEquals(OptionalDouble.of(-3), parseScore("-3"));
        assertEquals(OptionalDouble.of(1000), parseScore("1e3"));
        assertEquals(OptionalDouble.of(0.5), parseScore(".5"));
        assertEquals(OptionalDouble.of(16), parseScore("0x10"));
        assertEquals(OptionalDouble.of(31), parseScore("0x1f"));
        assertEquals(OptionalDouble.of(Double.POSITIVE_INFINITY), parseScore("+inf"));
        assertEquals(OptionalDouble.of(Double.POSITIVE_INFINITY), parseScore("Infinity"));
        assertEquals(OptionalDouble.of(Double.NEGATIVE_INFINITY), parseScore("-INF"));

        String[] rejected = {"", " 1", "1 ", "1.5d", "2f", "nan", "NaN", "abc", "1e400"};
        for (String text : rejected) {
            assertTrue(parseScore(text).isEmpty(), "점수로 받아들이면 안 된다: \"" + text + "\"");
        }
    }

    @Test
    @DisplayName("점수 출력 형식은 실제 Redis 7.4 와 동일")
    void formatsScores() {
        // 기대값 - 실제 Redis 7.4 에서 ZADD 후 ZSCORE 로 받은 값
        String[][] cases = {
                {"3", "3"}, {"-3", "-3"}, {"-0.0", "0"}, {"0.1", "0.1"}, {"123456.789", "123456.789"},
                {"0.00001", "0.00001"}, {"0.000001", "0.000001"}, {"-0.000001", "-0.000001"},
                {"1e-7", "1e-7"}, {"1.5e-7", "1.5e-7"}, {"123e-9", "1.23e-7"}, {"1.25e-10", "1.25e-10"},
                {"0.1234567890123", "0.1234567890123"}, {"1.2345678901234567e-5", "1.2345678901234568e-5"},
                {"1e17", "100000000000000000"}, {"1e18", "1000000000000000000"}, {"1.5e18", "1500000000000000000"},
                {"4.6e18", "4600000000000000000"}, {"4.7e18", "4.7e+18"}, {"1e19", "1e+19"}, {"1.25e19", "1.25e+19"}, {"123e17", "1.23e+19"}, {"-1e22", "-1e+22"},
                {"12345678901234567890", "12345678901234567000"}, {"9007199254740993", "9007199254740992"},
                {"1.7976931348623157e308", "1.7976931348623157e+308"}, {"5e-324", "5e-324"},
        };
        for (String[] c : cases) {
            assertEquals(c[1], format(Double.parseDouble(c[0])), "입력 " + c[0]);
        }
        assertEquals("inf", format(Double.POSITIVE_INFINITY));
        assertEquals("-inf", format(Double.NEGATIVE_INFINITY));
    }

    private static OptionalDouble parseScore(String text) {
        return Numbers.parseDouble(text.getBytes(StandardCharsets.US_ASCII));
    }

    private static String format(double value) {
        return new String(Numbers.formatDouble(value), StandardCharsets.US_ASCII);
    }

    private static OptionalLong parse(String text) {
        return Numbers.parseLong(text.getBytes(StandardCharsets.US_ASCII));
    }
}
