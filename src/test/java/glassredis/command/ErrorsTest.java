package glassredis.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ErrorsTest {

    @Test
    @DisplayName("모르는 명령 - 이름은 받은 그대로, 인자 앞부분은 따옴표로 표시")
    void unknownCommandShowsNameAndArgs() {
        assertEquals("ERR unknown command 'nope', with args beginning with: ",
                Errors.unknownCommand(bytes("nope"), List.of()).message());
        assertEquals("ERR unknown command 'FooBar', with args beginning with: 'a' 'b c' ",
                Errors.unknownCommand(bytes("FooBar"), List.of(bytes("a"), bytes("b c"))).message());
    }

    @Test
    @DisplayName("사용자 입력의 줄바꿈은 공백으로 치환 - 에러 응답이 두 줄로 쪼개지면 안 됨")
    void unknownCommandReplacesNewlines() {
        assertEquals("ERR unknown command 'a  b', with args beginning with: 'x y' ",
                Errors.unknownCommand(bytes("a\r\nb"), List.of(bytes("x\ny"))).message());
    }

    @Test
    @DisplayName("이름은 128 바이트까지, 인자 목록은 128 바이트 초과 전까지만 포함")
    void unknownCommandTruncatesLongInput() {
        String name = "n".repeat(200);
        assertEquals("ERR unknown command '" + "n".repeat(128) + "', with args beginning with: ",
                Errors.unknownCommand(bytes(name), List.of()).message());

        // 첫 인자 100바이트로 목록 103바이트('...'와 공백) - 둘째 인자는 25바이트만 포함
        // 128 초과로 셋째 인자 제외
        String message = Errors.unknownCommand(bytes("x"),
                List.of(bytes("a".repeat(100)), bytes("b".repeat(100)), bytes("c"))).message();
        assertEquals("ERR unknown command 'x', with args beginning with: '"
                + "a".repeat(100) + "' '" + "b".repeat(25) + "' ", message);
    }

    @Test
    @DisplayName("모르는 EXPIRE 옵션은 받은 그대로 표시 - 끝 줄바꿈은 제거, 중간 줄바꿈은 공백으로 치환")
    void unsupportedOption() {
        assertEquals("ERR Unsupported option foo", Errors.unsupportedOption(bytes("foo")).message());
        assertEquals("ERR Unsupported option x y", Errors.unsupportedOption(bytes("x\ny\r\n")).message());
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
