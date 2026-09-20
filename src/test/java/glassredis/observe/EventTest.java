package glassredis.observe;

import glassredis.observe.Event.RemovalReason;
import glassredis.resp.RespValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 이벤트를 만들 때 값이 화면에 그릴 수 있는 형태로 줄어드는지 본다.
 *
 * <p>여기서 줄이지 않으면 버퍼가 값 크기에 비례해 부풀고, 대시보드는 수 MB 짜리 줄을 받게 된다.
 */
class EventTest {

    @Test
    @DisplayName("긴 값은 잘라서 담는다")
    void truncatesLongValues() {
        String rendered = arguments("x".repeat(1000));

        assertEquals(Display.MAX_TEXT_LENGTH + 1, rendered.length(), "자른 표시 한 글자가 더 붙는다");
        assertTrue(rendered.endsWith("…"), rendered);
    }

    @Test
    @DisplayName("인자가 많으면 앞의 몇 개만 보여주고 나머지는 개수로 알린다")
    void summarisesExtraArguments() {
        String[] args = new String[Display.MAX_ARGUMENTS + 3];
        for (int i = 0; i < args.length; i++) {
            args[i] = "a" + i;
        }

        String rendered = arguments(args);

        assertTrue(rendered.startsWith("a0 a1 "), rendered);
        assertTrue(rendered.endsWith(" …(+3)"), rendered);
    }

    @Test
    @DisplayName("제어문자는 점으로 바꾼다 — 한 줄짜리 스트림에 개행이 섞이면 표가 깨진다")
    void replacesControlCharacters() {
        assertEquals("a.b", arguments("a\nb"));
    }

    @Test
    @DisplayName("한글은 그대로 둔다")
    void keepsNonAsciiText() {
        assertEquals("안녕 세계", arguments("안녕 세계"));
    }

    @Test
    @DisplayName("한 글자를 두 자리로 표현하는 값이 잘려도 깨진 문자를 남기지 않는다")
    void neverLeavesHalfOfACharacter() {
        // 이모지는 char 두 개로 표현된다. 앞에 한 글자를 붙여 자르는 자리가 그 둘 사이에 오게 만든다.
        String rendered = arguments("a" + "\uD83D\uDE00".repeat(Display.MAX_TEXT_LENGTH / 2));

        assertTrue(rendered.endsWith("…"), rendered);
        // 짝을 잃은 자리가 남아 있으면 UTF-8 로 바꿀 때 '?' 로 뭉개져 되돌려도 원래 문자열과 달라진다.
        assertEquals(rendered, new String(rendered.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8),
                "잘린 자리에 깨진 문자가 남았다: " + rendered);
    }

    @Test
    @DisplayName("응답은 RESP 타입 기호를 살려 한 줄로 줄인다")
    void rendersReplyByType() {
        assertEquals("+OK", reply(RespValue.OK));
        assertEquals(":42", reply(new RespValue.Int(42)));
        assertEquals("(nil)", reply(RespValue.NIL));
        assertEquals("-ERR 뭔가 잘못됨", reply(new RespValue.Err("ERR 뭔가 잘못됨")));
        assertEquals("hello", reply(RespValue.BulkString.of("hello")));
        assertEquals("(2개)", reply(RespValue.Array.of(RespValue.OK, RespValue.NIL)));
    }

    @Test
    @DisplayName("사라진 키도 같은 규칙으로 줄인다")
    void rendersRemovedKey() {
        Event.KeyRemoved removed = Event.keyRemoved("k".repeat(1000).getBytes(StandardCharsets.UTF_8),
                RemovalReason.ACTIVE_EXPIRED, 30);

        assertEquals(Display.MAX_TEXT_LENGTH + 1, removed.key().length());
        assertEquals(30, removed.lateByMillis());
    }

    private static String arguments(String... args) {
        List<byte[]> bytes = new ArrayList<>();
        for (String arg : args) {
            bytes.add(arg.getBytes(StandardCharsets.UTF_8));
        }
        return Event.command(1, "STUB", bytes, 0, RespValue.OK).arguments();
    }

    private static String reply(RespValue value) {
        return Event.command(1, "STUB", List.of(), 0, value).reply();
    }
}
