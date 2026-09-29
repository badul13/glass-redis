package glassredis.store;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * 바이트 배열 키 - 내용 기준 equals/hashCode
 * 복사 없음 - 맵에 넣은 뒤 배열 수정 금지
 */
public record Key(byte[] bytes) {

    public Key {
        Objects.requireNonNull(bytes, "bytes");
    }

    public static Key of(String text) {
        return new Key(text.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Key other && Arrays.equals(bytes, other.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return "Key[" + new String(bytes, StandardCharsets.UTF_8) + "]";
    }
}
