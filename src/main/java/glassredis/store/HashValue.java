package glassredis.store;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Hash. 키 하나 안에 든 작은 맵(필드 → 값).
 *
 * <p>필드도 바이너리 세이프라 {@link Key} 로 감싸서 내용으로 비교되게 한다. 이유는 {@code Key} 에 적힌 것과 같다.
 *
 * <p>{@code LinkedHashMap} 이라 넣은 순서가 유지된다. Redis 는 Hash 의 순서를 약속하지 않으므로
 * 어떤 순서여도 틀린 건 아니지만, {@code HGETALL} 을 칠 때마다 순서가 뒤섞이면 화면에서 읽기 어렵다.
 */
public final class HashValue implements Value {

    private final Map<Key, byte[]> fields = new LinkedHashMap<>();

    /** 제자리에서 고쳐 쓴다. 비워둔 채로 두면 안 된다 — 빈 Hash 는 키째로 지운다. */
    public Map<Key, byte[]> fields() {
        return fields;
    }

    @Override
    public String typeName() {
        return "hash";
    }

    @Override
    public int size() {
        return fields.size();
    }
}
