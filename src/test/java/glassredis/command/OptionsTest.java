package glassredis.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptionsTest {

    @Test
    @DisplayName("옵션 비교는 strcasecmp - ASCII 대소문자 무관, NUL 에서 끝")
    void comparesLikeStrcasecmp() {
        assertTrue(Options.is(bytes("nx"), "NX"));
        assertTrue(Options.is(bytes("Nx"), "NX"));
        assertTrue(Options.is(bytes("NX\u0000junk"), "NX"));
        assertTrue(Options.is(bytes("withscores\u0000"), "WITHSCORES"));

        assertFalse(Options.is(bytes("N"), "NX"));
        assertFalse(Options.is(bytes("NXX"), "NX"));
        assertFalse(Options.is(bytes("N\u0000X"), "NX"));
        assertFalse(Options.is(bytes(""), "NX"));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
