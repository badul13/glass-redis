package glassredis.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ErrorsTest {

    @Test
    @DisplayName("모르는 명령은 이름을 받은 그대로 두고, 인자 앞부분을 따옴표로 보여준다")
    void unknownCommandShowsNameAndArgs() {
        assertEquals("ERR unknown command 'nope', with args beginning with: ",
                Errors.unknownCommand(bytes("nope"), List.of()).message());
        assertEquals("ERR unknown command 'FooBar', with args beginning with: 'a' 'b c' ",
                Errors.unknownCommand(bytes("FooBar"), List.of(bytes("a"), bytes("b c"))).message());
    }

    @Test
    @DisplayName("사용자 입력에 섞인 줄바꿈은 공백으로 바꾼다 — 에러 응답이 두 줄로 쪼개지면 안 된다")
    void unknownCommandReplacesNewlines() {
        assertEquals("ERR unknown command 'a  b', with args beginning with: 'x y' ",
                Errors.unknownCommand(bytes("a\r\nb"), List.of(bytes("x\ny"))).message());
    }

    @Test
    @DisplayName("이름은 128 바이트까지, 인자 목록은 128 바이트를 넘기 전까지만 담는다")
    void unknownCommandTruncatesLongInput() {
        String name = "n".repeat(200);
        assertEquals("ERR unknown command '" + "n".repeat(128) + "', with args beginning with: ",
                Errors.unknownCommand(bytes(name), List.of()).message());

        // 첫 인자가 100 바이트면 목록이 103 바이트('...' 와 공백). 둘째 인자에는 128-103=25 바이트만 남는다.
        // 목록이 128 을 넘었으므로 셋째 인자는 담지 않는다.
        String message = Errors.unknownCommand(bytes("x"),
                List.of(bytes("a".repeat(100)), bytes("b".repeat(100)), bytes("c"))).message();
        assertEquals("ERR unknown command 'x', with args beginning with: '"
                + "a".repeat(100) + "' '" + "b".repeat(25) + "' ", message);
    }

    @Test
    @DisplayName("모르는 EXPIRE 옵션은 받은 그대로 보여주되, 끝의 줄바꿈은 지우고 중간 것은 공백으로 바꾼다")
    void unsupportedOption() {
        assertEquals("ERR Unsupported option foo", Errors.unsupportedOption(bytes("foo")).message());
        assertEquals("ERR Unsupported option x y", Errors.unsupportedOption(bytes("x\ny\r\n")).message());
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
