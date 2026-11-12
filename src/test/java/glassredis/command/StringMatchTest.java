package glassredis.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StringMatchTest {

    @Test
    @DisplayName("* ? 기본 매칭")
    void wildcards() {
        assertMatch(true, "*", "anything");
        assertMatch(true, "h?llo", "hello");
        assertMatch(false, "h?llo", "hllo");
        assertMatch(true, "h*llo", "hllo");
        assertMatch(true, "h*llo", "heeeello");
        assertMatch(true, "user:*:name", "user:42:name");
        assertMatch(false, "user:*:name", "user:42:age");
        assertMatch(true, "a**b", "ab");
        assertMatch(true, "abc*", "abc");
        assertMatch(false, "", "a");
        assertMatch(false, "?", "");
    }

    @Test
    @DisplayName("빈 문자열 - 루프 진입 전 종료라 * 와도 불일치 (KEYS * 는 지름길로 포함)")
    void emptyStringNeverMatchesNonEmptyPattern() {
        assertMatch(false, "*", "");
        assertMatch(false, "**", "");
        assertMatch(true, "", "");
    }

    @Test
    @DisplayName("[] 문자 집합 - 부정, 범위, 뒤집힌 범위")
    void brackets() {
        assertMatch(true, "h[ae]llo", "hallo");
        assertMatch(false, "h[ae]llo", "hillo");
        assertMatch(true, "h[^e]llo", "hallo");
        assertMatch(false, "h[^e]llo", "hello");
        assertMatch(true, "h[a-b]llo", "hbllo");
        assertMatch(true, "h[b-a]llo", "hallo");
        assertMatch(false, "h[a-b]llo", "hcllo");
        assertMatch(true, "[\\]]", "]");
    }

    @Test
    @DisplayName("닫히지 않은 [ - 끝까지 집합으로 해석")
    void unterminatedBracket() {
        assertMatch(true, "[ab", "a");
        assertMatch(true, "[ab", "b");
        assertMatch(false, "[ab", "c");
        assertMatch(false, "[ab", "ab");
    }

    @Test
    @DisplayName("\\ 이스케이프 - 다음 글자를 문자 그대로, 끝의 \\ 는 \\ 자신")
    void escapes() {
        assertMatch(true, "a\\*b", "a*b");
        assertMatch(false, "a\\*b", "axb");
        assertMatch(true, "a\\", "a\\");
    }

    @Test
    @DisplayName("nocase - ASCII 대소문자 무시, 범위에도 적용")
    void nocase() {
        assertEquals(true, StringMatch.matches(bytes("HeLLo"), bytes("hello"), true));
        assertEquals(true, StringMatch.matches(bytes("[A-C]x"), bytes("bX"), true));
        assertEquals(false, StringMatch.matches(bytes("HeLLo"), bytes("hello"), false));
    }

    @Test
    @DisplayName("skipLongerMatches - * 많은 실패 패턴도 즉시 종료")
    void pathologicalPatternIsFast() {
        String pattern = "a*".repeat(30) + "b";
        String string = "a".repeat(60);
        long start = System.nanoTime();
        assertMatch(false, pattern, string);
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertEquals(true, millis < 1_000, "가지치기 없으면 지수 시간: " + millis + "ms");
    }

    private static void assertMatch(boolean expected, String pattern, String string) {
        assertEquals(expected, StringMatch.matches(bytes(pattern), bytes(string), false),
                pattern + " ~ " + string);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
