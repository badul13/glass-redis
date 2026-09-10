package glassredis.resp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RespReaderTest {

    @Test
    @DisplayName("배열 형식 명령을 argv 로 읽는다")
    void arrayCommand() throws IOException {
        List<byte[]> argv = reader("*2\r\n$4\r\nECHO\r\n$5\r\nhello\r\n").readCommand();

        assertEquals(2, argv.size());
        assertEquals("ECHO", text(argv.get(0)));
        assertEquals("hello", text(argv.get(1)));
    }

    @Test
    @DisplayName("인자 안에 CRLF 가 있어도 길이대로 잘라낸다")
    void binarySafeArgument() throws IOException {
        // 길이 6 = a b CR LF c d. 구분자를 찾는 방식이었다면 여기서 잘못 잘렸을 것이다.
        List<byte[]> argv = reader("*2\r\n$4\r\nECHO\r\n$6\r\nab\r\ncd\r\n").readCommand();

        assertArrayEquals(new byte[]{'a', 'b', '\r', '\n', 'c', 'd'}, argv.get(1));
    }

    @Test
    @DisplayName("빈 인자도 읽어낸다")
    void emptyArgument() throws IOException {
        List<byte[]> argv = reader("*2\r\n$4\r\nECHO\r\n$0\r\n\r\n").readCommand();

        assertEquals(2, argv.size());
        assertEquals(0, argv.get(1).length);
    }

    @Test
    @DisplayName("한 버퍼에 명령이 여러 개 붙어 와도 하나씩 읽는다")
    void pipelinedCommands() throws IOException {
        RespReader reader = reader("*1\r\n$4\r\nPING\r\n*1\r\n$4\r\nPING\r\n");

        assertEquals("PING", text(reader.readCommand().get(0)));
        assertEquals("PING", text(reader.readCommand().get(0)));
        assertNull(reader.readCommand());
    }

    @Test
    @DisplayName("인라인 명령을 공백으로 자른다")
    void inlineCommand() throws IOException {
        List<byte[]> argv = reader("ECHO hello\r\n").readCommand();

        assertEquals(2, argv.size());
        assertEquals("ECHO", text(argv.get(0)));
        assertEquals("hello", text(argv.get(1)));
    }

    @Test
    @DisplayName("인라인은 LF 만 있어도, 공백이 여러 개여도 처리한다")
    void inlineToleratesWhitespace() throws IOException {
        List<byte[]> argv = reader("  PING   extra \n").readCommand();

        assertEquals(2, argv.size());
        assertEquals("PING", text(argv.get(0)));
        assertEquals("extra", text(argv.get(1)));
    }

    @Test
    @DisplayName("빈 줄은 빈 명령이 된다")
    void blankLine() throws IOException {
        assertTrue(reader("\r\n").readCommand().isEmpty());
    }

    @Test
    @DisplayName("스트림이 끝나면 null 을 준다")
    void endOfStream() throws IOException {
        assertNull(reader("").readCommand());
    }

    @Test
    @DisplayName("배열 원소가 벌크 문자열이 아니면 프로토콜 에러")
    void rejectsNonBulkArgument() {
        assertThrows(RespProtocolException.class, () -> reader("*1\r\n+PING\r\n").readCommand());
    }

    @Test
    @DisplayName("길이 자리에 숫자가 아닌 것이 오면 프로토콜 에러")
    void rejectsNonNumericLength() {
        assertThrows(RespProtocolException.class, () -> reader("*abc\r\n").readCommand());
    }

    @Test
    @DisplayName("터무니없이 큰 벌크 길이는 메모리를 잡기 전에 거절한다")
    void rejectsHugeBulkLength() {
        // 검사가 없으면 이 한 줄로 서버가 OutOfMemoryError 로 죽는다.
        assertThrows(RespProtocolException.class,
                () -> reader("*1\r\n$999999999999\r\n").readCommand());
    }

    @Test
    @DisplayName("벌크 데이터 뒤에 CRLF 가 없으면 프로토콜 에러")
    void rejectsMissingTrailingCrlf() {
        assertThrows(RespProtocolException.class,
                () -> reader("*1\r\n$4\r\nPINGxx").readCommand());
    }

    @Test
    @DisplayName("readValue 는 임의의 RESP 값을 읽는다")
    void readsArbitraryValues() throws IOException {
        assertEquals(new RespValue.SimpleString("OK"), reader("+OK\r\n").readValue());
        assertEquals(new RespValue.Err("ERR nope"), reader("-ERR nope\r\n").readValue());
        assertEquals(new RespValue.Int(1000), reader(":1000\r\n").readValue());
        assertEquals(RespValue.BulkString.of("hello"), reader("$5\r\nhello\r\n").readValue());
        assertEquals(RespValue.NIL, reader("$-1\r\n").readValue());
        assertEquals(RespValue.NIL, reader("*-1\r\n").readValue());
        assertEquals(RespValue.Array.of(new RespValue.Int(1), new RespValue.Int(2)),
                reader("*2\r\n:1\r\n:2\r\n").readValue());
    }

    private static RespReader reader(String raw) {
        InputStream in = new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8));
        return new RespReader(in);
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
