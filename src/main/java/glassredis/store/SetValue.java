package glassredis.store;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Set. 중복 없는 원소 모음.
 *
 * <p>원소는 {@link Key} 로 감싸서 내용으로 비교되게 한다. 이유는 {@code Key} 에 적힌 것과 같다.
 * {@code LinkedHashSet} 인 이유는 {@link HashValue} 와 같다 — 순서를 약속하지는 않지만 화면에서 읽기 쉽게.
 */
public final class SetValue implements Value {

    private final Set<Key> members = new LinkedHashSet<>();

    /** 제자리에서 고쳐 쓴다. 비워둔 채로 두면 안 된다 — 빈 Set 은 키째로 지운다. */
    public Set<Key> members() {
        return members;
    }

    @Override
    public String typeName() {
        return "set";
    }

    @Override
    public int size() {
        return members.size();
    }
}
