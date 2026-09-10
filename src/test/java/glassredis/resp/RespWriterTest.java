package glassredis.resp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 직렬화 결과가 프로토콜 스펙의 바이트와 정확히 같은지 확인한다.
 * 여기서 한 바이트라도 어긋나면 redis-cli 가 붙지 않는다.
 */
class RespWriterTest {

    @Test
    @DisplayName("단순 문자열은 + 로 시작하고 CRLF 로 끝난다")
    void simpleString() throws IOException {
        assertWrites(RespValue.OK, "+OK\r\n");
        assertWrites(RespValue.PONG, "+PONG\r\n");
    }

    @Test
    @DisplayName("에러는 - 로 시작한다")
    void error() throws IOException {
        assertWrites(new RespValue.Err("ERR unknown command 'asdf'"), "-ERR unknown command 'asdf'\r\n");
    }

    @Test
    @DisplayName("정수는 : 로 시작하고 음수도 그대로 쓴다")
    void integer() throws IOException {
        assertWrites(new RespValue.Int(0), ":0\r\n");
        assertWrites(new RespValue.Int(1000), ":1000\r\n");
        assertWrites(new RespValue.Int(-1), ":-1\r\n");
    }

    @Test
    @DisplayName("벌크 문자열은 길이를 먼저 쓴다")
    void bulkString() throws IOException {
        assertWrites(RespValue.BulkString.of("hello"), "$5\r\nhello\r\n");
    }

    @Test
    @DisplayName("빈 벌크 문자열과 널 벌크 문자열은 다르다")
    void emptyIsNotNull() throws IOException {
        assertWrites(RespValue.BulkString.of(""), "$0\r\n\r\n");
        assertWrites(RespValue.NIL, "$-1\r\n");
    }

    @Test
    @DisplayName("벌크 문자열의 길이는 문자 수가 아니라 바이트 수다")
    void bulkLengthIsBytes() throws IOException {
        // '한' 은 UTF-8 로 3바이트다. 문자 수(1)를 쓰면 클라이언트가 프레임을 잘못 잘라낸다.
        assertWrites(RespValue.BulkString.of("한"), "$3\r\n한\r\n");
    }

    @Test
    @DisplayName("벌크 문자열 안에 CRLF 가 들어가도 그대로 실어 보낸다")
    void bulkStringIsBinarySafe() throws IOException {
        byte[] payload = {'a', 'b', '\r', '\n', 'c', 'd'};
        assertWrites(new RespValue.BulkString(payload), "$6\r\nab\r\ncd\r\n");
    }

    @Test
    @DisplayName("배열은 개수를 먼저 쓰고 원소를 이어 붙인다")
    void array() throws IOException {
        RespValue value = RespValue.Array.of(RespValue.BulkString.of("hello"), RespValue.BulkString.of("world"));
        assertWrites(value, "*2\r\n$5\r\nhello\r\n$5\r\nworld\r\n");
        assertWrites(RespValue.EMPTY_ARRAY, "*0\r\n");
    }

    @Test
    @DisplayName("배열은 중첩될 수 있다")
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
