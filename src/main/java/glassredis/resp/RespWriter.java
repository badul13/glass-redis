package glassredis.resp;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** 버퍼링 - 응답 하나를 다 쓴 뒤 flush() 필요 */
public final class RespWriter {

    private static final byte[] CRLF = {'\r', '\n'};
    private static final byte[] NULL_BULK = "$-1\r\n".getBytes(StandardCharsets.US_ASCII);

    private final OutputStream out;

    public RespWriter(OutputStream out) {
        this.out = out instanceof BufferedOutputStream buffered ? buffered : new BufferedOutputStream(out, 8192);
    }

    public void write(RespValue value) throws IOException {
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
                    write(item);
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
