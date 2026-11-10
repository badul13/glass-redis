package glassredis.resp;

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
            byte first = buf[p];
            if (first != '*') {
                // 인라인 - 빈 줄은 즉시, 아니면 줄 끝까지
                if (first == '\n' || first == '\r') {
                    return true;
                }
                return lineEnd(p + 1) >= 0 || count - p > MAX_INLINE_LENGTH;
            }

            long arguments = scanNumber(p + 1);
            if (arguments == NEED_MORE) {
                return false;
            }
            if (arguments == MALFORMED || arguments < 0 || arguments > MAX_ARRAY_LENGTH) {
                return true;
            }
            p = next;
            for (long i = 0; i < arguments; i++) {
                if (p >= count) {
                    return false;
                }
                if (buf[p] != '$') {
                    return true;
                }
                long length = scanNumber(p + 1);
                if (length == NEED_MORE) {
                    return false;
                }
                if (length == MALFORMED || length < 0 || length > MAX_BULK_LENGTH) {
                    return true;
                }
                // 데이터 + CRLF
                long end = (long) next + length + 2;
                if (end > count) {
                    return false;
                }
                p = (int) end;
            }
            return true;
        }

        /** from 부터 숫자 줄 하나 - 성공 시 next 에 줄 다음 위치 */
        private long scanNumber(int from) {
            int end = lineEnd(from);
            if (end < 0) {
                return count - from > MAX_NUMBER_LINE ? MALFORMED : NEED_MORE;
            }
            int last = end > from && buf[end - 1] == '\r' ? end - 1 : end;
            if (last == from || last - from > MAX_NUMBER_LINE) {
                return MALFORMED;
            }
            try {
                long value = Long.parseLong(new String(buf, from, last - from, StandardCharsets.US_ASCII));
                next = end + 1;
                return value;
            } catch (NumberFormatException e) {
                return MALFORMED;
            }
        }

        /** buf[from, count) 의 첫 LF 위치, 없으면 -1 */
        private int lineEnd(int from) {
            for (int i = from; i < count; i++) {
                if (buf[i] == '\n') {
                    return i;
                }
            }
            return -1;
        }
    }
}
