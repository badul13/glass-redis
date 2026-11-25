package glassredis.store;

import glassredis.store.encoding.LongStrings;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

/**
 * 바이너리 세이프 문자열 - object.c tryObjectEncoding
 * int/embstr/raw 모두 byte[] 저장, 인코딩 이름만 Redis와 일치
 * 배열 수정 없이 새 객체 생성, 비교는 내용 기준 (Key 와 같은 방식)
 */
public final class StringValue implements Value {

    /** OBJ_ENCODING_EMBSTR_SIZE_LIMIT - jemalloc 64바이트 칸에서 머리 20바이트 제외 */
    static final int EMBSTR_SIZE_LIMIT = 44;

    /** OBJ_SHARED_INTEGERS - 0 이상 이 값 미만 int 인코딩 값은 공유 객체 (shared.integers) */
    public static final int SHARED_INTEGERS = 10000;

    public enum Form { INT, EMBSTR, RAW }

    private final byte[] bytes;
    private final Form form;

    public StringValue(byte[] bytes, Form form) {
        this.bytes = Objects.requireNonNull(bytes, "bytes");
        this.form = Objects.requireNonNull(form, "form");
    }

    /** 정수 표기면 int, 44바이트 이하면 embstr, 그 외 raw */
    public static StringValue of(byte[] bytes) {
        if (bytes.length <= 20 && LongStrings.parse(bytes) != null) {
            return new StringValue(bytes, Form.INT);
        }
        return new StringValue(bytes, bytes.length <= EMBSTR_SIZE_LIMIT ? Form.EMBSTR : Form.RAW);
    }

    public static StringValue ofLong(long value) {
        return new StringValue(LongStrings.toBytes(value), Form.INT);
    }

    /** APPEND 등 수정 결과는 길이 무관 raw */
    public static StringValue raw(byte[] bytes) {
        return new StringValue(bytes, Form.RAW);
    }

    /** 내부 배열 그대로 - 수정 금지 */
    public byte[] bytes() {
        return bytes;
    }

    public Form form() {
        return form;
    }

    /**
     * 공유 정수 객체 번호, 아니면 -1 - tryObjectEncoding·createStringObjectFromLongLongForValue
     * maxmemory 0 (noeviction) 기준 - int 인코딩이면서 0 이상 10000 미만
     * 공유 객체는 refcount INT_MAX, lru 를 키끼리 공유
     */
    public int sharedIntegerIndex() {
        if (form != Form.INT || bytes.length > 4) {
            return -1;
        }
        Long value = LongStrings.parse(bytes);
        return value != null && value >= 0 && value < SHARED_INTEGERS ? value.intValue() : -1;
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

    @Override
    public boolean equals(Object o) {
        return o instanceof StringValue other && form == other.form && Arrays.equals(bytes, other.bytes);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(bytes) + form.hashCode();
    }

    @Override
    public String toString() {
        return "StringValue[" + new String(bytes, StandardCharsets.UTF_8) + ", " + encoding() + "]";
    }
}
