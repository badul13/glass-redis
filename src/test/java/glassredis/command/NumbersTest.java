package glassredis.command;

import glassredis.store.ScoreRange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.OptionalDouble;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
            assertTrue(parse(text).isEmpty(), "정수로 거절할 입력: \"" + text + "\"");
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
            assertTrue(parseScore(text).isEmpty(), "점수로 거절할 입력: \"" + text + "\"");
        }
    }

    @Test
    @DisplayName("점수는 string2d 기준 - 0 으로 사라지는 언더플로·접미사·제어 문자·NUL 거절, 비정규 수는 허용")
    void parsesScoresLikeString2d() {
        // 기대값 - Redis 7.2.4 의 ZADD 응답 (value is not a valid float 여부)
        assertEquals(OptionalDouble.of(Double.MIN_VALUE), parseScore("5e-324"));
        assertEquals(OptionalDouble.of(1e-310), parseScore("1e-310"));
        assertEquals(OptionalDouble.of(8), parseScore("0x1p3"));
        assertEquals(OptionalDouble.of(0.5), parseScore("0x.8"));
        assertEquals(OptionalDouble.of(10), parseScore("1.e1"));

        String[] rejected = {"1e-400", "2e-324", "0x1p3f", "0x1p3d", "1\u0001", "\u00011", "\t1", "1\t", "1\u0000",
                "1e5000", "0x", "0x1p", "1e", ".e1", "-", "+", ".", "infx", "infin", "nan(1)", "-nan"};
        for (String text : rejected) {
            assertTrue(parseScore(text).isEmpty(), "점수로 거절할 입력: \"" + text + "\"");
        }
    }

    @Test
    @DisplayName("구간 끝 해석은 zslParseRange 기준 - strtod 끝 위치와 NaN 만 확인")
    void parsesRangeBounds() {
        // 기대값 - Redis 7.2.4 의 ZCOUNT 응답 (min or max is not a float 여부)
        assertEquals(new ScoreRange(0, false, 0, false), range("", ""));
        assertEquals(new ScoreRange(0, true, 5, true), range("(", "(5"));
        assertEquals(new ScoreRange(1, false, 5, false), range(" 1", "\t\u000b5"));
        assertEquals(new ScoreRange(Double.POSITIVE_INFINITY, false, Double.NEGATIVE_INFINITY, false),
                range("1e400", "-1e400"));
        assertEquals(new ScoreRange(0, false, 16, false), range("1e-400", "0x10"));
        assertEquals(new ScoreRange(Double.POSITIVE_INFINITY, false, Double.NEGATIVE_INFINITY, false),
                range("infinity", "-inFinity"));
        // NUL 에서 끝나는 C 문자열 - 뒤 바이트는 무시
        assertEquals(new ScoreRange(1, false, 5, true), range("1\u0000x", "(5\u0000"));

        String[] rejected = {"1 ", " ", "nan", "-nan", "(nan", "1\u0001", "0x1p3f", "1f", "0x", "1e", ".e1", "-",
                "infx", "infin", "nan(1)", "((1"};
        for (String text : rejected) {
            assertNull(range(text, "5"), "구간 끝으로 거절할 입력: \"" + text + "\"");
            assertNull(range("1", text), "구간 끝으로 거절할 입력: \"" + text + "\"");
        }
    }

    @Test
    @DisplayName("점수 출력 형식은 실제 Redis 7.2.4 와 동일")
    void formatsScores() {
        // 기대값 - 실제 Redis 7.2.4 에서 ZADD 후 ZSCORE 로 받은 값
        // -0 은 skiplist 인코딩 ZSCORE 값 (d2string) - listpack 인코딩은 저장 때 정수 0 이 되어 "0"
        String[][] cases = {
                {"3", "3"}, {"-3", "-3"}, {"-0.0", "-0"}, {"0.1", "0.1"}, {"123456.789", "123456.789"},
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

    private static ScoreRange range(String min, String max) {
        return ScoreRanges.parse(min.getBytes(StandardCharsets.UTF_8), max.getBytes(StandardCharsets.UTF_8));
    }

    private static OptionalDouble parseScore(String text) {
        return Numbers.parseDouble(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String format(double value) {
        return new String(Numbers.formatDouble(value), StandardCharsets.US_ASCII);
    }

    private static OptionalLong parse(String text) {
        return Numbers.parseLong(text.getBytes(StandardCharsets.US_ASCII));
    }
}
