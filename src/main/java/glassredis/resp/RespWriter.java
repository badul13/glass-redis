package glassredis.resp;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * {@link RespValue} 를 RESP 바이트로 직렬화한다.
 *
 * <p>소켓 스트림에 직접 쓰면 {@code +PONG\r\n} 6바이트에도 시스템 콜이 여러 번 나가므로
 * 버퍼를 한 겹 둔다. 대신 버퍼는 명시적으로 {@link #flush()} 하기 전까지 나가지 않으니,
 * 응답 하나를 다 쓴 뒤 반드시 flush 해야 한다.
 */
public final class RespWriter {

    private static final byte[] CRLF = {'\r', '\n'};
    private static final byte[] NULL_BULK = "$-1\r\n".getBytes(StandardCharsets.US_ASCII);

    private final OutputStream out;

    public RespWriter(OutputStream out) {
        this.out = out instanceof BufferedOutputStream buffered ? buffered : new BufferedOutputStream(out, 8192);
    }

    public void write(RespValue value) throws IOException {
        // sealed interface 라서 default 가 필요 없다. 타입을 추가하면 여기서 컴파일 에러가 난다.
        switch (value) {
            case RespValue.SimpleString simple -> writeLine('+', simple.text());
            case RespValue.Err error -> writeLine('-', error.message());
            case RespValue.Int number -> writeLine(':', Long.toString(number.value()));
            case RespValue.BulkString bulk -> {
                writeLine('$', Integer.toString(bulk.bytes().length));
                out.write(bulk.bytes());
                out.write(CRLF);
            }
            case RespValue.Array array -> {
                writeLine('*', Integer.toString(array.items().size()));
                for (RespValue item : array.items()) {
                    write(item); // 배열은 중첩될 수 있다
                }
            }
            case RespValue.Nil ignored -> out.write(NULL_BULK);
        }
    }

    public void flush() throws IOException {
        out.flush();
    }

    private void writeLine(char prefix, String body) throws IOException {
        out.write(prefix);
        out.write(body.getBytes(StandardCharsets.UTF_8));
        out.write(CRLF);
    }
}
