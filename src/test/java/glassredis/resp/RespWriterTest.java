package glassredis.resp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RespWriterTest {

    @Test
    @DisplayName("단순 문자열 - + 로 시작, CRLF 로 끝")
    void simpleString() throws IOException {
        assertWrites(RespValue.OK, "+OK\r\n");
        assertWrites(RespValue.PONG, "+PONG\r\n");
    }

    @Test
    @DisplayName("에러는 - 로 시작")
    void error() throws IOException {
        assertWrites(new RespValue.Err("ERR unknown command 'asdf'"), "-ERR unknown command 'asdf'\r\n");
    }

    @Test
    @DisplayName("정수는 : 로 시작, 음수도 그대로 출력")
    void integer() throws IOException {
        assertWrites(new RespValue.Int(0), ":0\r\n");
        assertWrites(new RespValue.Int(1000), ":1000\r\n");
        assertWrites(new RespValue.Int(-1), ":-1\r\n");
    }

    @Test
    @DisplayName("벌크 문자열은 길이 먼저 출력")
    void bulkString() throws IOException {
        assertWrites(RespValue.BulkString.of("hello"), "$5\r\nhello\r\n");
    }

    @Test
    @DisplayName("빈 벌크 문자열과 널 벌크 문자열의 구별")
    void emptyIsNotNull() throws IOException {
        assertWrites(RespValue.BulkString.of(""), "$0\r\n\r\n");
        assertWrites(RespValue.NIL, "$-1\r\n");
    }

    @Test
    @DisplayName("벌크 문자열 길이는 문자 수가 아닌 바이트 수")
    void bulkLengthIsBytes() throws IOException {
        // '한' 은 UTF-8 로 3바이트
        assertWrites(RespValue.BulkString.of("한"), "$3\r\n한\r\n");
    }

    @Test
    @DisplayName("벌크 문자열 안의 CRLF 도 그대로 전송")
    void bulkStringIsBinarySafe() throws IOException {
        byte[] payload = {'a', 'b', '\r', '\n', 'c', 'd'};
        assertWrites(new RespValue.BulkString(payload), "$6\r\nab\r\ncd\r\n");
    }

    @Test
    @DisplayName("배열 - 개수 먼저, 원소는 이어서 출력")
    void array() throws IOException {
        RespValue value = RespValue.Array.of(RespValue.BulkString.of("hello"), RespValue.BulkString.of("world"));
        assertWrites(value, "*2\r\n$5\r\nhello\r\n$5\r\nworld\r\n");
        assertWrites(RespValue.EMPTY_ARRAY, "*0\r\n");
    }

    @Test
    @DisplayName("배열 중첩 가능")
    void nestedArray() throws IOException {
        RespValue inner = new RespValue.Array(List.of(new RespValue.Int(1), new RespValue.Int(2)));
        RespValue outer = new RespValue.Array(List.of(inner, RespValue.OK));
        assertWrites(outer, "*2\r\n*2\r\n:1\r\n:2\r\n+OK\r\n");
    }

    private static void assertWrites(RespValue value, String expected) throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        RespWriter writer = new RespWriter(sink);
        writer.write(value);
        writer.flush();
        assertEquals(expected, sink.toString(StandardCharsets.UTF_8));
    }
}
