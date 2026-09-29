package glassredis.resp;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** RESP2 값 - sealed라 switch가 default 없이 누락 검출 */
public sealed interface RespValue {

    /** CR/LF 포함 시 프레이밍 손상 - 생성 시점 차단 */
    record SimpleString(String text) implements RespValue {
        public SimpleString {
            Objects.requireNonNull(text, "text");
            requireNoCrlf(text);
        }
    }

    /** 첫 단어 - ERR, WRONGTYPE 같은 대문자 접두어 */
    record Err(String message) implements RespValue {
        public Err {
            Objects.requireNonNull(message, "message");
            requireNoCrlf(message);
        }
    }

    record Int(long value) implements RespValue {}

    /** 바이너리 세이프 - 임의 바이트라 String 디코딩 생략 */
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

        // record 기본 equals는 배열 참조 비교
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

    record Array(List<RespValue> items) implements RespValue {
        public Array {
            items = List.copyOf(items);
        }

        public static Array of(RespValue... items) {
            return new Array(List.of(items));
        }
    }

    /** 출력 형태 - $-1\r\n */
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
