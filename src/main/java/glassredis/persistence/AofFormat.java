package glassredis.persistence;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * AOF 한 줄 - 클라이언트가 보내는 RESP 배열 그대로 (catAppendOnlyGenericCommand)
 * "*인자수\r\n" + 인자마다 "$길이\r\n내용\r\n"
 */
final class AofFormat {

    private static final byte[] CRLF = {'\r', '\n'};

    private AofFormat() {
    }

    static void command(ByteArrayOutputStream out, List<byte[]> argv) {
        header(out, '*', argv.size());
        for (byte[] arg : argv) {
            bulk(out, arg);
        }
    }

    /** rioWriteBulkCount */
    static void header(ByteArrayOutputStream out, char prefix, long count) {
        out.write(prefix);
        out.writeBytes(Long.toString(count).getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(CRLF);
    }

    /** rioWriteBulkString */
    static void bulk(ByteArrayOutputStream out, byte[] arg) {
        header(out, '$', arg.length);
        out.writeBytes(arg);
        out.writeBytes(CRLF);
    }

    static void bulk(ByteArrayOutputStream out, String ascii) {
        bulk(out, ascii.getBytes(StandardCharsets.US_ASCII));
    }

    static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }
}
