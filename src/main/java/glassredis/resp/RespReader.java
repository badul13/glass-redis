package glassredis.resp;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * RESP 읽기 - 배열 형식(*2\r\n$4\r\nECHO...) + 인라인 형식(ECHO hello\r\n)
 * 첫 바이트가 * 아니면 인라인
 */
public final class RespReader {

    /** proto-max-bulk-len 기본값 */
    public static final int MAX_BULK_LENGTH = 512 * 1024 * 1024;

    public static final int MAX_ARRAY_LENGTH = 1024 * 1024;

    /** 줄바꿈 없이 계속 보내는 클라이언트 차단 */
    public static final int MAX_INLINE_LENGTH = 64 * 1024;

    /** $123, *2 같은 숫자 줄의 최대 길이 */
    private static final int MAX_NUMBER_LINE = 32;

    private final InputStream in;

    public RespReader(InputStream in) {
        this.in = in instanceof BufferedInputStream buffered ? buffered : new BufferedInputStream(in, 8192);
    }

    /**
     * 명령 하나를 argv로 읽기
     *
     * @return 클라이언트 끊김 시 null, 빈 줄·빈 배열이면 빈 리스트
     * @throws RespProtocolException 프레이밍 손상 시
     */
    public List<byte[]> readCommand() throws IOException {
        int first = in.read();
        if (first == -1) {
            return null;
        }
        if (first == '*') {
            return readArrayCommand();
        }
        return readInlineCommand(first);
    }

    /** EOF면 null */
    public RespValue readValue() throws IOException {
        int type = in.read();
        if (type == -1) {
            return null;
        }
        return readValue(type);
    }

    /** * 읽은 뒤 호출 */
    private List<byte[]> readArrayCommand() throws IOException {
        long count = readNumberLine();
        if (count < 0 || count > MAX_ARRAY_LENGTH) {
            throw new RespProtocolException("invalid multibulk length");
        }
        List<byte[]> argv = new ArrayList<>((int) count);
        for (long i = 0; i < count; i++) {
            int type = in.read();
            if (type == -1) {
                throw new RespProtocolException("unexpected end of stream in multibulk");
            }
            if (type != '$') {
                throw new RespProtocolException("expected a bulk string, got type byte " + describe(type));
            }
            byte[] argument = readBulkBody();
            if (argument == null) {
                throw new RespProtocolException("unexpected null argument");
            }
            argv.add(argument);
        }
        return argv;
    }

    /** 공백 기준 분리만 - Redis와 달리 따옴표 미처리 */
    private List<byte[]> readInlineCommand(int firstByte) throws IOException {
        if (firstByte == '\n' || firstByte == '\r') {
            return List.of();
        }
        byte[] rest = readLine(MAX_INLINE_LENGTH);
        byte[] line = new byte[rest.length + 1];
        line[0] = (byte) firstByte;
        System.arraycopy(rest, 0, line, 1, rest.length);

        List<byte[]> argv = new ArrayList<>();
        int i = 0;
        while (i < line.length) {
            while (i < line.length && isBlank(line[i])) {
                i++;
            }
            int start = i;
            while (i < line.length && !isBlank(line[i])) {
                i++;
            }
            if (i > start) {
                argv.add(Arrays.copyOfRange(line, start, i));
            }
        }
        return argv;
    }

    private RespValue readValue(int type) throws IOException {
        return switch (type) {
            case '+' -> new RespValue.SimpleString(readTextLine());
            case '-' -> new RespValue.Err(readTextLine());
            case ':' -> new RespValue.Int(readNumberLine());
            case '$' -> {
                byte[] body = readBulkBody();
                yield body == null ? RespValue.NIL : new RespValue.BulkString(body);
            }
            case '*' -> {
                long count = readNumberLine();
                if (count == -1) {
                    yield RespValue.NIL; // *-1
                }
                if (count < 0 || count > MAX_ARRAY_LENGTH) {
                    throw new RespProtocolException("invalid multibulk length");
                }
                List<RespValue> items = new ArrayList<>((int) count);
                for (long i = 0; i < count; i++) {
                    RespValue item = readValue();
                    if (item == null) {
                        throw new RespProtocolException("unexpected end of stream in array");
                    }
                    items.add(item);
                }
                yield new RespValue.Array(items);
            }
            default -> throw new RespProtocolException("unknown type byte " + describe(type));
        };
    }

    /** $ 뒤의 <길이>\r\n<데이터>\r\n - 길이 -1이면 null */
    private byte[] readBulkBody() throws IOException {
        long length = readNumberLine();
        if (length == -1) {
            return null;
        }
        // 할당 전 검사 - 헤더 한 줄로 OOM 유발 가능
        if (length < 0 || length > MAX_BULK_LENGTH) {
            throw new RespProtocolException("invalid bulk length");
        }
        byte[] data = new byte[(int) length];
        readFully(data);
        expectCrlf();
        return data;
    }

    /** 종결자 뺀 한 줄 - LF 단독도 허용 */
    private byte[] readLine(int limit) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(32);
        while (true) {
            int b = in.read();
            if (b == -1) {
                throw new RespProtocolException("unexpected end of stream while reading a line");
            }
            if (b == '\n') {
                byte[] line = buffer.toByteArray();
                if (line.length > 0 && line[line.length - 1] == '\r') {
                    return Arrays.copyOf(line, line.length - 1);
                }
                return line;
            }
            if (buffer.size() >= limit) {
                throw new RespProtocolException("too big inline request");
            }
            buffer.write(b);
        }
    }

    private String readTextLine() throws IOException {
        return new String(readLine(MAX_INLINE_LENGTH), StandardCharsets.UTF_8);
    }

    private long readNumberLine() throws IOException {
        String text = new String(readLine(MAX_NUMBER_LINE), StandardCharsets.US_ASCII);
        if (text.isEmpty()) {
            throw new RespProtocolException("expected a number, got an empty line");
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new RespProtocolException("expected a number, got " + text);
        }
    }

    /** read 1회로 다 채운다는 보장 없음 - 루프 */
    private void readFully(byte[] destination) throws IOException {
        int offset = 0;
        while (offset < destination.length) {
            int read = in.read(destination, offset, destination.length - offset);
            if (read == -1) {
                throw new RespProtocolException("unexpected end of stream while reading bulk data");
            }
            offset += read;
        }
    }

    private void expectCrlf() throws IOException {
        int cr = in.read();
        int lf = in.read();
        if (cr != '\r' || lf != '\n') {
            throw new RespProtocolException("expected CRLF after bulk data");
        }
    }

    private static boolean isBlank(byte b) {
        return b == ' ' || b == '\t';
    }

    private static String describe(int typeByte) {
        return (typeByte >= 0x20 && typeByte < 0x7f)
                ? String.valueOf((char) typeByte)
                : ("0x" + Integer.toHexString(typeByte));
    }
}
