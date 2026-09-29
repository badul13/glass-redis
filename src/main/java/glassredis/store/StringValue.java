package glassredis.store;

import glassredis.store.encoding.Listpack;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;

/**
 * 바이너리 세이프 문자열 - object.c tryObjectEncoding
 * int/embstr/raw 모두 byte[] 저장, 인코딩 이름만 Redis와 일치
 * 배열 수정 없이 새 객체 생성
 */
public record StringValue(byte[] bytes, Form form) implements Value {

    /** OBJ_ENCODING_EMBSTR_SIZE_LIMIT - jemalloc 64바이트 칸에서 머리 20바이트 제외 */
    static final int EMBSTR_SIZE_LIMIT = 44;

    public enum Form { INT, EMBSTR, RAW }

    public StringValue {
        Objects.requireNonNull(bytes, "bytes");
        Objects.requireNonNull(form, "form");
    }

    /** 정수 표기면 int, 44바이트 이하면 embstr, 그 외 raw */
    public static StringValue of(byte[] bytes) {
        if (bytes.length <= 20 && Listpack.toInt64(bytes) != null) {
            return new StringValue(bytes, Form.INT);
        }
        return new StringValue(bytes, bytes.length <= EMBSTR_SIZE_LIMIT ? Form.EMBSTR : Form.RAW);
    }

    public static StringValue ofLong(long value) {
        return new StringValue(Long.toString(value).getBytes(StandardCharsets.US_ASCII), Form.INT);
    }

    /** APPEND 등 수정 결과는 길이 무관 raw */
    public static StringValue raw(byte[] bytes) {
        return new StringValue(bytes, Form.RAW);
    }

    @Override
    public String typeName() {
        return "string";
    }

    @Override
    public String encoding() {
        return form.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public int size() {
        return bytes.length;
    }
}
