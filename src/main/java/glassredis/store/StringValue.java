package glassredis.store;

import java.util.Objects;

/**
 * 문자열. 이름은 문자열이지만 실제로는 아무 바이트열이다. 정수도 따로 타입이 있는 게 아니라
 * 정수처럼 생긴 문자열일 뿐이다({@code INCR} 이 매번 해석한다).
 *
 * <p>배열은 한 번 담은 뒤로 고치지 않는다. 값을 바꾸는 명령은 새 배열로 새 객체를 만든다.
 */
public record StringValue(byte[] bytes) implements Value {

    public StringValue {
        Objects.requireNonNull(bytes, "bytes");
    }

    @Override
    public String typeName() {
        return "string";
    }

    @Override
    public int size() {
        return bytes.length;
    }
}
