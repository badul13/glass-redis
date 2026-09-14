package glassredis.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NumbersTest {

    @Test
    @DisplayName("평범한 정수와 long 범위의 양 끝을 읽는다")
    void parsesValidIntegers() {
        assertEquals(OptionalLong.of(0), parse("0"));
        assertEquals(OptionalLong.of(42), parse("42"));
        assertEquals(OptionalLong.of(-42), parse("-42"));
        assertEquals(OptionalLong.of(Long.MAX_VALUE), parse("9223372036854775807"));
        assertEquals(OptionalLong.of(Long.MIN_VALUE), parse("-9223372036854775808"));
    }

    @Test
    @DisplayName("Redis 가 정수로 보지 않는 표기는 거절한다")
    void rejectsNonCanonicalOrOutOfRange() {
        String[] rejected = {
                "", "-", "+5", "007", "-0", "1.5", " 1", "1 ", "12a",
                "9223372036854775808", "-9223372036854775809", "123456789012345678901",
        };
        for (String text : rejected) {
            assertTrue(parse(text).isEmpty(), "정수로 받아들이면 안 된다: \"" + text + "\"");
        }
    }

    private static OptionalLong parse(String text) {
        return Numbers.parseLong(text.getBytes(StandardCharsets.US_ASCII));
    }
}
