package glassredis.resp;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * RESP2 값 하나.
 *
 * <p>RESP 는 첫 바이트로 타입을 구분하고, 모든 조각을 CRLF 로 끝낸다.
 * 여기서는 그 타입들을 sealed 계층으로 표현한다. sealed 이므로 구현체가 이 파일 안으로 닫히고,
 * 덕분에 {@code switch} 에서 default 없이도 컴파일러가 누락을 잡아준다.
 * 나중에 RESP3 타입(Map, Set, Double, Push)을 추가하면 고쳐야 할 지점을 컴파일러가 전부 알려준다.
 *
 * <pre>
 *   +OK\r\n                          SimpleString
 *   -ERR unknown command 'foo'\r\n   Err
 *   :1000\r\n                        Int
 *   $5\r\nhello\r\n                  BulkString
 *   *2\r\n$5\r\nhello\r\n$5\r\nworld\r\n   Array
 *   $-1\r\n                          Nil (RESP2 에는 null 타입이 없어 길이 -1 로 표현한다)
 * </pre>
 */
public sealed interface RespValue {

    /**
     * {@code +OK} 처럼 짧은 비바이너리 문자열. 오버헤드가 가장 작다.
     * CR/LF 를 담을 수 없다 — 담으면 프레이밍이 깨지므로 생성 시점에 막는다.
     */
    record SimpleString(String text) implements RespValue {
        public SimpleString {
            Objects.requireNonNull(text, "text");
            requireNoCrlf(text);
        }
    }

    /**
     * 에러 응답. 형식은 SimpleString 과 같고 첫 바이트만 {@code -} 다.
     * 관례상 첫 단어는 대문자 에러 접두어({@code ERR}, {@code WRONGTYPE} 등)로 시작한다.
     */
    record Err(String message) implements RespValue {
        public Err {
            Objects.requireNonNull(message, "message");
            requireNoCrlf(message);
        }
    }

    /** 부호 있는 64비트 정수. */
    record Int(long value) implements RespValue {}

    /**
     * 바이너리 세이프 문자열. 길이를 먼저 보내므로 데이터 안에 CRLF 가 있어도 된다.
     *
     * <p>값을 {@code String} 이 아니라 {@code byte[]} 로 들고 다니는 이유가 여기 있다.
     * 사용자가 넣는 값은 이미지든 직렬화된 객체든 아무 바이트열이나 될 수 있는데,
     * 그걸 UTF-8 문자열로 디코딩해버리면 되돌릴 수 없게 깨진다.
     */
    record BulkString(byte[] bytes) implements RespValue {
        public BulkString {
            Objects.requireNonNull(bytes, "bytes");
        }

        public static BulkString of(String text) {
            return new BulkString(text.getBytes(StandardCharsets.UTF_8));
        }

        public String asText() {
            return new String(bytes, StandardCharsets.UTF_8);
        }

        // record 가 자동 생성하는 equals 는 배열을 '참조'로 비교한다.
        // 내용 비교가 되도록 직접 구현한다. 안 그러면 테스트가 전부 실패한다.
        @Override
        public boolean equals(Object o) {
            return o instanceof BulkString other && Arrays.equals(bytes, other.bytes);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(bytes);
        }

        @Override
        public String toString() {
            return "BulkString[" + asText() + "]";
        }
    }

    /** 배열. 클라이언트가 보내는 명령이 바로 이 형태(벌크 문자열의 배열)다. */
    record Array(List<RespValue> items) implements RespValue {
        public Array {
            items = List.copyOf(items);
        }

        public static Array of(RespValue... items) {
            return new Array(List.of(items));
        }
    }

    /** 값 없음. 나갈 때는 널 벌크 문자열 {@code $-1\r\n} 로 쓴다. */
    record Nil() implements RespValue {}

    RespValue NIL = new Nil();
    SimpleString OK = new SimpleString("OK");
    SimpleString PONG = new SimpleString("PONG");
    Array EMPTY_ARRAY = new Array(List.of());

    private static void requireNoCrlf(String text) {
        if (text.indexOf('\r') >= 0 || text.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("단순 문자열/에러에는 CR 또는 LF 를 넣을 수 없습니다: " + text);
        }
    }
}
