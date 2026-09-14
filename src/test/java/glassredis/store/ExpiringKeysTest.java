package glassredis.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpiringKeysTest {

    private final ExpiringKeys keys = new ExpiringKeys();

    @Test
    @DisplayName("중간 키를 지우면 마지막 키가 빈자리로 옮겨가고, 남은 키는 계속 지울 수 있다")
    void swapRemoveKeepsIndexConsistent() {
        add("a", "b", "c", "d");

        keys.remove(Key.of("b")); // d 가 b 자리로 옮겨간다
        assertEquals(3, keys.size());
        assertFalse(keys.contains(Key.of("b")));

        // 옮겨간 d 의 위치 정보가 틀렸다면 여기서 엉뚱한 키가 지워진다.
        keys.remove(Key.of("d"));
        assertEquals(Set.of("a", "c"), contents());

        keys.remove(Key.of("a"));
        keys.remove(Key.of("c"));
        assertEquals(0, keys.size());
    }

    @Test
    @DisplayName("같은 키를 두 번 넣어도 하나로 세고, 없는 키를 지워도 아무 일도 없다")
    void addIsIdempotentAndRemoveIgnoresMissing() {
        add("a", "a");
        keys.remove(Key.of("never-added"));

        assertEquals(1, keys.size());
    }

    @Test
    @DisplayName("무작위 선택은 들어 있는 키만 고르고, 모든 키가 뽑힐 수 있다")
    void randomPicksOnlyMembers() {
        add("a", "b", "c");
        keys.remove(Key.of("b"));

        SplittableRandom random = new SplittableRandom(42);
        Set<Key> picked = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            picked.add(keys.random(random));
        }
        assertEquals(Set.of(Key.of("a"), Key.of("c")), picked);
    }

    private void add(String... names) {
        for (String name : names) {
            keys.add(Key.of(name));
        }
    }

    /** 무작위로 충분히 뽑아서 들어 있는 키 이름을 모은다. 내부 배열을 들여다보지 않고 확인하기 위해서다. */
    private Set<String> contents() {
        SplittableRandom random = new SplittableRandom(7);
        Set<String> names = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            Key key = keys.random(random);
            assertTrue(keys.contains(key));
            names.add(new String(key.bytes()));
        }
        return names;
    }
}
