package glassredis.store;

import glassredis.store.encoding.Listpack;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;

/**
 * 문자열. 이름은 문자열이지만 실제로는 아무 바이트열이다.
 *
 * <p>Redis 는 같은 문자열도 세 가지 모양 중 하나로 담는다(object.c 의 tryObjectEncoding).
 * <ul>
 *   <li><b>int</b> — {@code "12345"} 처럼 되돌렸을 때 똑같아지는 정수 표기면 문자열 대신 long 하나로 담는다.
 *       {@code INCR} 의 결과도 늘 이것이다.</li>
 *   <li><b>embstr</b> — 44바이트 이하. C 판은 객체 머리와 문자열을 한 번의 메모리 할당에 붙여 담는다.
 *       할당 한 번으로 끝나고 캐시에도 한 덩어리로 올라간다. 대신 고칠 수 없다.</li>
 *   <li><b>raw</b> — 그보다 길거나, {@code APPEND} 처럼 제자리에서 고친 문자열. 객체와 문자열을 따로 할당한다.</li>
 * </ul>
 * 44 라는 숫자는 C 판의 메모리 할당 단위(jemalloc 의 64바이트 칸)에서 나온다. 객체 머리 16바이트와
 * 문자열 머리 3바이트, 끝의 NUL 1바이트를 빼면 64 - 20 = 44 가 남는다.
 *
 * <p>자바에서는 셋 다 {@code byte[]} 로 들고 있고, 어느 모양인지는 이름표로만 기억한다. 메모리 배치까지 흉내 낼 수는
 * 없지만 {@code OBJECT ENCODING} 이 무엇을 답하는지는 실제 Redis 와 같게 맞춘다.
 * 배열은 한 번 담은 뒤로 고치지 않는다. 값을 바꾸는 명령은 새 객체를 만든다.
 */
public record StringValue(byte[] bytes, Form form) implements Value {

    /** 44바이트 이하면 embstr(OBJ_ENCODING_EMBSTR_SIZE_LIMIT). */
    static final int EMBSTR_SIZE_LIMIT = 44;

    /** 담는 모양. {@link #encoding()} 이 소문자로 바꿔 {@code OBJECT ENCODING} 에 답한다. */
    public enum Form { INT, EMBSTR, RAW }

    public StringValue {
        Objects.requireNonNull(bytes, "bytes");
        Objects.requireNonNull(form, "form");
    }

    /** 클라이언트가 보낸 값을 담을 때(SET, MSET 등). 모양을 알아서 고른다(tryObjectEncoding). */
    public static StringValue of(byte[] bytes) {
        if (bytes.length <= 20 && Listpack.toInt64(bytes) != null) {
            return new StringValue(bytes, Form.INT);
        }
        return new StringValue(bytes, bytes.length <= EMBSTR_SIZE_LIMIT ? Form.EMBSTR : Form.RAW);
    }

    /** 정수 결과(INCR 등). 늘 int 다. */
    public static StringValue ofLong(long value) {
        return new StringValue(Long.toString(value).getBytes(StandardCharsets.US_ASCII), Form.INT);
    }

    /** 제자리에서 고친 결과(APPEND 등). 길이와 상관없이 raw 다. */
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
