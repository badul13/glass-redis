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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RespReaderTest {

    @Test
    @DisplayName("배열 형식 명령을 argv 로 파싱")
    void arrayCommand() throws IOException {
        List<byte[]> argv = reader("*2\r\n$4\r\nECHO\r\n$5\r\nhello\r\n").readCommand();

        assertEquals(2, argv.size());
        assertEquals("ECHO", text(argv.get(0)));
        assertEquals("hello", text(argv.get(1)));
    }

    @Test
    @DisplayName("인자 안에 CRLF 가 있어도 길이 기준으로 절단")
    void binarySafeArgument() throws IOException {
        // 길이 6 = a b CR LF c d
        List<byte[]> argv = reader("*2\r\n$4\r\nECHO\r\n$6\r\nab\r\ncd\r\n").readCommand();

        assertArrayEquals(new byte[]{'a', 'b', '\r', '\n', 'c', 'd'}, argv.get(1));
    }

    @Test
    @DisplayName("빈 인자도 파싱")
    void emptyArgument() throws IOException {
        List<byte[]> argv = reader("*2\r\n$4\r\nECHO\r\n$0\r\n\r\n").readCommand();

        assertEquals(2, argv.size());
        assertEquals(0, argv.get(1).length);
    }

    @Test
    @DisplayName("한 버퍼에 명령이 여러 개 붙어 와도 하나씩 파싱")
    void pipelinedCommands() throws IOException {
        RespReader reader = reader("*1\r\n$4\r\nPING\r\n*1\r\n$4\r\nPING\r\n");

        assertEquals("PING", text(reader.readCommand().get(0)));
        assertEquals("PING", text(reader.readCommand().get(0)));
        assertNull(reader.readCommand());
    }

    @Test
    @DisplayName("인라인 명령 - 공백 기준 분리")
    void inlineCommand() throws IOException {
        List<byte[]> argv = reader("ECHO hello\r\n").readCommand();

        assertEquals(2, argv.size());
        assertEquals("ECHO", text(argv.get(0)));
        assertEquals("hello", text(argv.get(1)));
    }

    @Test
    @DisplayName("인라인 - LF 만 있어도, 공백이 여러 개여도 처리")
    void inlineToleratesWhitespace() throws IOException {
        List<byte[]> argv = reader("  PING   extra \n").readCommand();

        assertEquals(2, argv.size());
        assertEquals("PING", text(argv.get(0)));
        assertEquals("extra", text(argv.get(1)));
    }

    @Test
    @DisplayName("빈 줄은 빈 명령")
    void blankLine() throws IOException {
        assertTrue(reader("\r\n").readCommand().isEmpty());
    }

    @Test
    @DisplayName("스트림 종료 시 null")
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
    @DisplayName("터무니없이 큰 벌크 길이는 메모리 할당 전에 거절")
    void rejectsHugeBulkLength() {
        // 검사가 없으면 OutOfMemoryError 발생
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
    @DisplayName("readValue - 임의의 RESP 값 파싱")
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

    @Test
    @DisplayName("hasBufferedCommand - 한 번에 받은 명령이 남아 있는 동안만 true")
    void bufferedCommandsInOneRead() throws IOException {
        RespReader reader = reader("*1\r\n$4\r\nPING\r\nECHO hi\r\n*1\r\n$4\r\nPING\r\n");

        // 첫 readCommand 전에는 아직 아무것도 안 받음
        assertFalse(reader.hasBufferedCommand());
        reader.readCommand();
        assertTrue(reader.hasBufferedCommand());
        reader.readCommand();
        assertTrue(reader.hasBufferedCommand());
        reader.readCommand();
        assertFalse(reader.hasBufferedCommand());
    }

    @Test
    @DisplayName("hasBufferedCommand - 덜 들어온 명령은 false (소켓 대기 필요)")
    void partialCommandIsNotBuffered() throws IOException {
        // 뒷부분이 아직 안 온 상황들
        for (String tail : List.of("*", "*2\r", "*2\r\n$4\r\nECHO\r\n", "*2\r\n$4\r\nECHO\r\n$5\r\nhel",
                "*2\r\n$4\r\nECHO\r\n$5\r\nhello\r", "ECHO hi")) {
            RespReader reader = reader("PING\r\n" + tail);
            reader.readCommand();
            assertFalse(reader.hasBufferedCommand(), "덜 들어온 꼬리: " + tail.replace("\r\n", "\\r\\n"));
        }
    }

    @Test
    @DisplayName("hasBufferedCommand - 프레이밍 손상은 true, 이어지는 readCommand 가 바로 예외")
    void malformedCommandIsReportedAsBuffered() throws IOException {
        for (String tail : List.of("*abc\r\n", "*1\r\n+PING\r\n", "*1\r\n$-1\r\n")) {
            RespReader reader = reader("PING\r\n" + tail);
            reader.readCommand();
            assertTrue(reader.hasBufferedCommand(), "손상된 꼬리: " + tail.replace("\r\n", "\\r\\n"));
            assertThrows(RespProtocolException.class, reader::readCommand);
        }
    }

    @Test
    @DisplayName("버퍼(16KB)보다 큰 인자도 그대로 읽음")
    void argumentLargerThanBuffer() throws IOException {
        String big = "x".repeat(RespReader.IO_BUFFER_SIZE * 3 + 7);
        RespReader reader = reader("*2\r\n$4\r\nECHO\r\n$" + big.length() + "\r\n" + big + "\r\nPING\r\n");

        List<byte[]> argv = reader.readCommand();
        assertEquals(big, text(argv.get(1)));
        assertTrue(reader.hasBufferedCommand());
        assertEquals("PING", text(reader.readCommand().get(0)));
        assertNull(reader.readCommand());
    }

    private static RespReader reader(String raw) {
        InputStream in = new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8));
        return new RespReader(in);
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
