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
 * 바이트 스트림에서 RESP 를 읽어낸다.
 *
 * <p>TCP 는 "메시지"가 아니라 "바이트 흐름"만 보장한다. 클라이언트가 한 번에 보낸 명령이
 * 여러 조각으로 쪼개져 도착할 수도 있고, 여러 명령이 한 덩어리로 붙어 올 수도 있다.
 * 그래서 어디까지가 한 명령인지는 응용 프로토콜이 정해야 하고, RESP 는 그걸
 * 길이 접두어와 CRLF 로 정한다. 이 클래스가 그 규칙을 해석하는 자리다.
 *
 * <p>서버가 실제로 쓰는 입구는 {@link #readCommand()} 하나다.
 * 명령은 두 가지 형태로 들어올 수 있다.
 *
 * <ul>
 *   <li><b>배열 형식</b> — 정상적인 클라이언트(redis-cli 등)가 쓰는 형태.
 *       {@code *2\r\n$4\r\nECHO\r\n$5\r\nhello\r\n}</li>
 *   <li><b>인라인 형식</b> — telnet 처럼 손으로 칠 때. {@code ECHO hello\r\n}<br>
 *       어떤 명령도 {@code *} 로 시작하지 않기 때문에, 첫 바이트가 {@code *} 가 아니면
 *       인라인이라고 판단할 수 있다. 프로토콜 스펙에 정식으로 있는 기능이다.</li>
 * </ul>
 *
 * <p>{@link #readValue()} 는 임의의 RESP 값을 읽는다. 지금은 테스트에서 쓰고,
 * 나중에 레플리카가 마스터의 응답을 읽을 때 다시 쓰게 된다.
 */
public final class RespReader {

    /** 벌크 문자열 최대 길이. 실제 Redis 의 기본값(proto-max-bulk-len)과 같은 512MB. */
    public static final int MAX_BULK_LENGTH = 512 * 1024 * 1024;

    /** 명령 배열의 최대 원소 수. */
    public static final int MAX_ARRAY_LENGTH = 1024 * 1024;

    /** 인라인 명령 한 줄의 최대 길이. 줄바꿈 없이 무한정 보내는 클라이언트를 막는다. */
    public static final int MAX_INLINE_LENGTH = 64 * 1024;

    /** 숫자 헤더 줄({@code $123}, {@code *2})의 최대 길이. */
    private static final int MAX_NUMBER_LINE = 32;

    private final InputStream in;

    public RespReader(InputStream in) {
        this.in = in instanceof BufferedInputStream buffered ? buffered : new BufferedInputStream(in, 8192);
    }

    /**
     * 명령 하나를 argv 형태로 읽는다. argv[0] 이 명령 이름, 나머지가 인자다.
     *
     * @return 스트림이 정상적으로 끝나면(클라이언트가 끊으면) {@code null}.
     *         빈 줄이나 빈 배열이면 빈 리스트 — 호출한 쪽에서 그냥 건너뛰면 된다.
     * @throws RespProtocolException 프레이밍이 깨져서 더 읽을 수 없을 때
     */
    public List<byte[]> readCommand() throws IOException {
        int first = in.read();
        if (first == -1) {
            return null; // EOF: 클라이언트가 커넥션을 닫았다
        }
        if (first == '*') {
            return readArrayCommand();
        }
        return readInlineCommand(first);
    }

    /** 임의의 RESP 값 하나를 읽는다. EOF 면 {@code null}. */
    public RespValue readValue() throws IOException {
        int type = in.read();
        if (type == -1) {
            return null;
        }
        return readValue(type);
    }

    // --- 명령 파싱 -------------------------------------------------------

    /** {@code *} 를 이미 읽은 상태에서 호출된다. */
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
            // 클라이언트가 보내는 명령의 원소는 반드시 벌크 문자열이어야 한다.
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

    /**
     * 인라인 명령. 첫 바이트는 이미 읽었으므로 되돌려 붙인 뒤 공백으로 자른다.
     *
     * <p>실제 Redis 는 인라인에서도 따옴표 처리를 하지만, 여기서는 공백 분리까지만 한다.
     * 인라인은 손으로 찔러보는 용도이므로 그걸로 충분하다.
     */
    private List<byte[]> readInlineCommand(int firstByte) throws IOException {
        if (firstByte == '\n' || firstByte == '\r') {
            return List.of(); // 그냥 엔터만 친 경우
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

    // --- 값 파싱 ---------------------------------------------------------

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
                    yield RespValue.NIL; // 널 배열 *-1
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

    /**
     * {@code $} 를 읽은 뒤의 본문. {@code <길이>\r\n<데이터>\r\n} 을 소비한다.
     *
     * @return 길이가 -1(널 벌크 문자열)이면 {@code null}
     */
    private byte[] readBulkBody() throws IOException {
        long length = readNumberLine();
        if (length == -1) {
            return null;
        }
        // 길이 검사를 여기서 해야 한다. 검사 없이 new byte[length] 를 하면
        // 헤더 한 줄로 서버를 OutOfMemoryError 로 죽일 수 있다.
        if (length < 0 || length > MAX_BULK_LENGTH) {
            throw new RespProtocolException("invalid bulk length");
        }
        byte[] data = new byte[(int) length];
        readFully(data);
        expectCrlf();
        return data;
    }

    // --- 바이트 수준 유틸 -------------------------------------------------

    /** CRLF 까지 한 줄을 읽어 종결자를 뺀 바이트를 준다. LF 만 와도 받아준다. */
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

    /** {@code $}, {@code *}, {@code :} 뒤에 오는 숫자 줄. */
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

    /**
     * 길이를 아는 데이터는 한 번에 읽는다. 내용을 한 바이트씩 훑을 필요가 전혀 없다 —
     * 길이 접두어 프로토콜의 핵심 이점이 이것이다.
     *
     * <p>{@code read} 한 번이 요청한 만큼을 다 채워준다는 보장이 없으므로 루프로 감싼다.
     * TCP 는 바이트 흐름이라 데이터가 나눠서 도착하는 게 정상이다.
     */
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
