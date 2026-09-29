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

/** 이벤트 생성 시 값이 표시용 길이로 줄어드는지 확인 */
class EventTest {

    @Test
    @DisplayName("긴 값은 잘라서 저장")
    void truncatesLongValues() {
        String rendered = arguments("x".repeat(1000));

        assertEquals(Display.MAX_TEXT_LENGTH + 1, rendered.length(), "자른 표시 한 글자가 더 붙는다");
        assertTrue(rendered.endsWith("…"), rendered);
    }

    @Test
    @DisplayName("인자가 많으면 앞의 몇 개만 표시, 나머지는 개수로 표기")
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
    @DisplayName("제어문자는 점으로 치환 - 한 줄짜리 스트림에 개행이 섞이면 표 깨짐")
    void replacesControlCharacters() {
        assertEquals("a.b", arguments("a\nb"));
    }

    @Test
    @DisplayName("한글은 그대로 유지")
    void keepsNonAsciiText() {
        assertEquals("안녕 세계", arguments("안녕 세계"));
    }

    @Test
    @DisplayName("두 자리로 표현되는 글자가 잘려도 깨진 문자 없음")
    void neverLeavesHalfOfACharacter() {
        // 이모지는 char 두 개 - 앞에 한 글자를 붙여 자르는 위치를 서로게이트 쌍 사이로 조정
        String rendered = arguments("a" + "\uD83D\uDE00".repeat(Display.MAX_TEXT_LENGTH / 2));

        assertTrue(rendered.endsWith("…"), rendered);
        // 짝 잃은 서로게이트는 UTF-8 왕복에서 '?' 로 변환
        assertEquals(rendered, new String(rendered.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8),
                "잘린 자리에 깨진 문자가 남았다: " + rendered);
    }

    @Test
    @DisplayName("응답은 RESP 타입 기호를 살려 한 줄로 축약")
    void rendersReplyByType() {
        assertEquals("+OK", reply(RespValue.OK));
        assertEquals(":42", reply(new RespValue.Int(42)));
        assertEquals("(nil)", reply(RespValue.NIL));
        assertEquals("-ERR 뭔가 잘못됨", reply(new RespValue.Err("ERR 뭔가 잘못됨")));
        assertEquals("hello", reply(RespValue.BulkString.of("hello")));
        assertEquals("(2개)", reply(RespValue.Array.of(RespValue.OK, RespValue.NIL)));
    }

    @Test
    @DisplayName("사라진 키도 같은 규칙으로 축약")
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
