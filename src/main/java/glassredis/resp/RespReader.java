package glassredis.resp;

import glassredis.command.Numbers;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;

/**
 * RESP 읽기 - 배열 형식(*2\r\n$4\r\nECHO...) + 인라인 형식(ECHO hello\r\n)
 * 첫 바이트가 * 아니면 인라인
 */
public final class RespReader {

    /** proto-max-bulk-len 기본값 */
    public static final int MAX_BULK_LENGTH = 512 * 1024 * 1024;

    public static final int MAX_ARRAY_LENGTH = 1024 * 1024;

    /** PROTO_INLINE_MAX_SIZE - 줄 끝 없이 쌓인 바이트 한도 (인라인·숫자 줄) */
    public static final int MAX_INLINE_LENGTH = 64 * 1024;

    /** $123, *2 같은 숫자 줄의 최대 길이 */
    private static final int MAX_NUMBER_LINE = 32;

    /** 소켓 read 1회 크기 - PROTO_IOBUF_LEN */
    public static final int IO_BUFFER_SIZE = 16 * 1024;

    private final QueryBuffer in;

    public RespReader(InputStream in) {
        this.in = new QueryBuffer(in);
    }

    /**
     * 이미 받은 바이트만으로 다음 명령을 끝까지 읽을 수 있는지 - 소켓 대기 없음
     * processInputBuffer 의 '버퍼에 완성된 명령이 남아 있는 동안' 조건
     * 프레이밍 손상도 true - 다음 readCommand() 가 대기 없이 예외
     */
    public boolean hasBufferedCommand() {
        return in.scanCommand();
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
        try {
            return first == '*' ? readMultibulkCommand() : readInlineCommand(first);
        } catch (EndOfStream e) {
            // 명령 중간 끊김 - 덜 받은 명령은 버리고 에러 응답 없이 종료 (freeClient)
            return null;
        }
    }

    /** EOF면 null */
    public RespValue readValue() throws IOException {
        int type = in.read();
        if (type == -1) {
            return null;
        }
        return readValue(type);
    }

    /**
     * * 읽은 뒤 호출 - processMultibulkBuffer
     * 숫자 줄은 \r 까지, 뒤 1바이트는 \n 으로 보고 확인 없이 건너뜀
     * 개수 0 이하는 빈 명령, 벌크 데이터 뒤 2바이트도 확인 없이 건너뜀
     */
    private List<byte[]> readMultibulkCommand() throws IOException {
        OptionalLong count = Numbers.parseLong(readUntilCr(1, "too big mbulk count string"));
        if (count.isEmpty() || count.getAsLong() > Integer.MAX_VALUE) {
            throw new RespProtocolException("invalid multibulk length");
        }
        if (count.getAsLong() <= 0) {
            return List.of();
        }
        int arguments = (int) count.getAsLong();
        // 개수만 믿고 크게 잡지 않음 - argv_len = min(multibulklen, 1024)
        List<byte[]> argv = new ArrayList<>(Math.min(arguments, 1024));
        for (int i = 0; i < arguments; i++) {
            byte[] line = readUntilCr(0, "too big bulk count string");
            // 빈 줄이면 Redis 가 보는 첫 바이트는 \r
            int type = line.length == 0 ? '\r' : line[0] & 0xff;
            if (type != '$') {
                throw new RespProtocolException("expected '$', got '" + printable(type) + "'");
            }
            OptionalLong length = Numbers.parseLong(Arrays.copyOfRange(line, 1, line.length));
            // 할당 전 검사 - 헤더 한 줄로 OOM 유발 가능
            if (length.isEmpty() || length.getAsLong() < 0 || length.getAsLong() > MAX_BULK_LENGTH) {
                throw new RespProtocolException("invalid bulk length");
            }
            byte[] data = new byte[(int) length.getAsLong()];
            readFullyOrEnd(data);
            skipOrEnd(2);
            argv.add(data);
        }
        return argv;
    }

    /**
     * 인라인 - processInlineBuffer
     * \n 까지 한 줄, 앞의 \r 제거, sdssplitargs 로 분리 (따옴표·이스케이프)
     */
    private List<byte[]> readInlineCommand(int firstByte) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(64);
        int b = firstByte;
        // 0x00 뒤로는 \n 이 와도 줄 끝 아님 - strchr 가 0x00 에서 멈춤
        boolean sawNul = false;
        while (b != '\n' || sawNul) {
            sawNul |= b == 0;
            line.write(b);
            b = nextLineByte(line.size(), "too big inline request");
        }
        byte[] bytes = line.toByteArray();
        int length = bytes.length > 0 && bytes[bytes.length - 1] == '\r' ? bytes.length - 1 : bytes.length;
        List<byte[]> argv = splitArgs(bytes, length);
        if (argv == null) {
            throw new RespProtocolException("unbalanced quotes in request");
        }
        return argv;
    }

    /**
     * sdssplitargs - 공백 구분, "..." 안 \n \r \t \b \a \xHH 이스케이프, '...' 안 \' 만
     * 닫는 따옴표 뒤는 공백이나 줄 끝만 허용, 0x00 은 C 문자열 끝 취급
     *
     * @return 따옴표 불균형이면 null
     */
    static List<byte[]> splitArgs(byte[] line, int length) {
        List<byte[]> argv = new ArrayList<>();
        int p = 0;
        while (true) {
            while (at(line, length, p) != 0 && isSpace(at(line, length, p))) {
                p++;
            }
            if (at(line, length, p) == 0) {
                return argv;
            }
            boolean inDouble = false;
            boolean inSingle = false;
            boolean done = false;
            ByteArrayOutputStream current = new ByteArrayOutputStream();
            while (!done) {
                int c = at(line, length, p);
                if (inDouble) {
                    if (c == '\\' && at(line, length, p + 1) == 'x'
                            && isHex(at(line, length, p + 2)) && isHex(at(line, length, p + 3))) {
                        current.write(Character.digit(at(line, length, p + 2), 16) * 16
                                + Character.digit(at(line, length, p + 3), 16));
                        p += 3;
                    } else if (c == '\\' && at(line, length, p + 1) != 0) {
                        p++;
                        current.write(switch (at(line, length, p)) {
                            case 'n' -> '\n';
                            case 'r' -> '\r';
                            case 't' -> '\t';
                            case 'b' -> '\b';
                            case 'a' -> 7;
                            default -> at(line, length, p);
                        });
                    } else if (c == '"') {
                        if (at(line, length, p + 1) != 0 && !isSpace(at(line, length, p + 1))) {
                            return null;
                        }
                        done = true;
                    } else if (c == 0) {
                        return null;
                    } else {
                        current.write(c);
                    }
                } else if (inSingle) {
                    if (c == '\\' && at(line, length, p + 1) == '\'') {
                        p++;
                        current.write('\'');
                    } else if (c == '\'') {
                        if (at(line, length, p + 1) != 0 && !isSpace(at(line, length, p + 1))) {
                            return null;
                        }
                        done = true;
                    } else if (c == 0) {
                        return null;
                    } else {
                        current.write(c);
                    }
                } else {
                    switch (c) {
                        case ' ', '\n', '\r', '\t', 0 -> done = true;
                        case '"' -> inDouble = true;
                        case '\'' -> inSingle = true;
                        default -> current.write(c);
                    }
                }
                if (at(line, length, p) != 0) {
                    p++;
                }
            }
            argv.add(current.toByteArray());
        }
    }

    /** 범위 밖은 0 - C 문자열 끝 */
    private static int at(byte[] line, int length, int i) {
        return i < length ? line[i] & 0xff : 0;
    }

    /** C isspace - 공백·\t·\n·\v·\f·\r */
    private static boolean isSpace(int c) {
        return c == ' ' || (c >= '\t' && c <= '\r');
    }

    private static boolean isHex(int c) {
        return Character.digit(c, 16) >= 0 && c < 0x80;
    }

    /** 에러 문구용 1바이트 - 줄바꿈은 공백 (addReplyError 가 \r\n 을 공백으로) */
    private static char printable(int b) {
        return b == '\r' || b == '\n' ? ' ' : (char) b;
    }

    /**
     * \r 까지 읽고 뒤 1바이트(\n 자리) 건너뜀 - \r 없이 64KB 넘으면 tooBig 에러
     * 0x00 뒤로는 \r 이 와도 줄 끝 아님 - strchr 가 0x00 에서 멈춤
     *
     * @param consumed 이 줄에서 이미 읽은 바이트 (* 같은 형식 바이트) - 한도 계산에 포함
     */
    private byte[] readUntilCr(int consumed, String tooBig) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(16);
        boolean sawNul = false;
        while (true) {
            int b = nextLineByte(consumed + line.size(), tooBig);
            if (b == '\r' && !sawNul) {
                skipOrEnd(1);
                return line.toByteArray();
            }
            sawNul |= b == 0;
            line.write(b);
        }
    }

    /**
     * 줄 끝을 못 찾은 채 소켓을 다시 읽어야 할 때 한도 검사 - 끊김이면 EndOfStream
     * Redis 는 read 1회 뒤 '줄 끝 없이 남은 바이트 > 64KB' 일 때만 에러 - 길이 자체가 아니라 read 경계 기준
     *
     * @param pending 줄 끝 없이 쌓인 바이트
     */
    private int nextLineByte(int pending, String tooBig) throws IOException {
        if (in.buffered() == 0 && pending > MAX_INLINE_LENGTH) {
            throw new RespProtocolException(tooBig);
        }
        int b = in.read();
        if (b == -1) {
            throw new EndOfStream();
        }
        return b;
    }

    private void readFullyOrEnd(byte[] destination) throws IOException {
        int offset = 0;
        while (offset < destination.length) {
            int read = in.read(destination, offset, destination.length - offset);
            if (read == -1) {
                throw new EndOfStream();
            }
            offset += read;
        }
    }

    private void skipOrEnd(int bytes) throws IOException {
        for (int i = 0; i < bytes; i++) {
            if (in.read() == -1) {
                throw new EndOfStream();
            }
        }
    }

    /** 명령 중간 끊김 - readCommand 가 null 로 변환 */
    private static final class EndOfStream extends IOException {
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
        return new String(readLine(MAX_INLINE_LENGTH), StandardCharsets.ISO_8859_1);
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

    private static String describe(int typeByte) {
        return (typeByte >= 0x20 && typeByte < 0x7f)
                ? String.valueOf((char) typeByte)
                : ("0x" + Integer.toHexString(typeByte));
    }

    /**
     * 소켓 앞 16KB 버퍼 - 아직 안 읽은 바이트 buf[pos, count) 들여다보기용
     * BufferedInputStream 상속 금지 - 하위 클래스면 내부 잠금이 synchronized 로 바뀜
     * synchronized 안 소켓 대기 = 가상 스레드가 캐리어 고정(JDK 21 pinning), 커넥션 수 > 코어 수면 교착
     * 커넥션 스레드 하나만 쓰므로 잠금 없음
     */
    private static final class QueryBuffer extends InputStream {

        /** 숫자 줄이 아직 덜 들어옴 */
        private static final long NEED_MORE = Long.MIN_VALUE;

        /** 숫자 줄 손상 - readCommand 가 대기 없이 예외 */
        private static final long MALFORMED = Long.MIN_VALUE + 1;

        private final InputStream in;

        private final byte[] buf = new byte[IO_BUFFER_SIZE];

        /** 다음에 읽을 위치 */
        private int pos;

        /** 받은 바이트 끝 */
        private int count;

        /** scanNumber 가 남기는 다음 위치 */
        private int next;

        QueryBuffer(InputStream in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            if (pos >= count && !fill()) {
                return -1;
            }
            return buf[pos++] & 0xff;
        }

        /** 버퍼에 남은 것 먼저, 비었으면 소켓 read 1회 - 큰 요청은 버퍼 거치지 않고 바로 */
        @Override
        public int read(byte[] destination, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            if (pos >= count) {
                if (length >= buf.length) {
                    return in.read(destination, offset, length);
                }
                if (!fill()) {
                    return -1;
                }
            }
            int n = Math.min(length, count - pos);
            System.arraycopy(buf, pos, destination, offset, n);
            pos += n;
            return n;
        }

        /** 소켓 대기 없이 읽을 수 있는 바이트 */
        int buffered() {
            return count - pos;
        }

        @Override
        public int available() throws IOException {
            return (count - pos) + in.available();
        }

        @Override
        public void close() throws IOException {
            in.close();
        }

        /** 빈 버퍼에 소켓 read 1회 - readQueryFromClient, EOF면 false */
        private boolean fill() throws IOException {
            int n = in.read(buf, 0, buf.length);
            if (n <= 0) {
                pos = count = 0;
                return false;
            }
            pos = 0;
            count = n;
            return true;
        }

        /** buf[pos, count) 안에서 명령 하나를 끝까지 따라가 봄 - readCommand 와 같은 판정 */
        boolean scanCommand() {
            int p = pos;
            if (p >= count) {
                return false;
            }
            if (buf[p] != '*') {
                // 인라인 - \n 까지 (64KB 초과는 버퍼 16KB 라 여기서 판정할 일 없음)
                return indexOf('\n', p) >= 0;
            }

            long arguments = scanNumber(p + 1, p + 1);
            if (arguments == NEED_MORE) {
                return false;
            }
            if (arguments == MALFORMED || arguments > Integer.MAX_VALUE || arguments <= 0) {
                return true;
            }
            p = next;
            for (long i = 0; i < arguments; i++) {
                // 숫자 줄이 다 와야 $ 확인 - processMultibulkBuffer 순서
                long length = scanNumber(p, p + 1);
                if (length == NEED_MORE || p >= count) {
                    return false;
                }
                if (buf[p] != '$' || length == MALFORMED || length < 0 || length > MAX_BULK_LENGTH) {
                    return true;
                }
                // 데이터 + 2바이트
                long end = (long) next + length + 2;
                if (end > count) {
                    return false;
                }
                p = (int) end;
            }
            return true;
        }

        /**
         * lineStart 부터 \r 까지 한 줄, 숫자는 digits 부터 - readUntilCr 와 같은 규칙
         * \r 뒤 1바이트까지 와야 완성, next 에 그 다음 위치
         */
        private long scanNumber(int lineStart, int digits) {
            int cr = indexOf('\r', lineStart);
            if (cr < 0 || cr + 1 >= count) {
                return NEED_MORE;
            }
            next = cr + 2;
            OptionalLong value = Numbers.parseLong(Arrays.copyOfRange(buf, Math.min(digits, cr), cr));
            return value.isPresent() ? value.getAsLong() : MALFORMED;
        }

        /** buf[from, count) 의 첫 target 위치, 없거나 0x00 이 먼저면 -1 (strchr) */
        private int indexOf(int target, int from) {
            for (int i = from; i < count; i++) {
                if (buf[i] == target) {
                    return i;
                }
                if (buf[i] == 0) {
                    return -1;
                }
            }
            return -1;
        }
    }
}
