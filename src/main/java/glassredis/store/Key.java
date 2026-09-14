package glassredis.store;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * 키. 바이트 배열을 감싸서 내용으로 비교되게 만든다.
 *
 * <p>Redis 의 키는 값과 마찬가지로 바이너리 세이프라서 {@code String} 이 아니라 {@code byte[]} 로 들고 다닌다.
 * 그런데 {@code byte[]} 는 {@code HashMap} 의 키로 그대로 쓸 수 없다. 배열의 {@code equals}/{@code hashCode} 는
 * 내용이 아니라 같은 객체인지를 보기 때문에, 같은 {@code "foo"} 라도 명령마다 새 배열로 읽혀 들어오면
 * 서로 다른 키가 된다. {@code SET foo 1} 을 한 뒤 {@code GET foo} 가 영원히 nil 을 주는 버그다.
 *
 * <p>감쌀 때 배열을 복사하지는 않는다. 키 배열은 {@code RespReader} 가 명령마다 새로 만들고 그 뒤로 아무도 고치지 않는다.
 * 복사하면 {@code GET} 한 번마다 할당이 하나씩 늘어난다. 대신 맵에 들어간 뒤 이 배열을 고치면
 * 해시 위치가 어긋나 키를 영영 못 찾게 되므로, 절대 고치지 않는다.
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
