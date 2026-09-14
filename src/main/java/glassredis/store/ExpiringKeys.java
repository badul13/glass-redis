package glassredis.store;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * 만료 시각이 있는 키의 집합. 추가, 삭제, 무작위 선택이 전부 O(1) 이다.
 *
 * <p>주기적 샘플링은 "만료 시각이 있는 키 중 무작위로 20개"를 골라야 한다.
 * 그런데 {@code HashMap} 에는 무작위로 하나를 꺼내는 연산이 없다. 순서대로 훑는 방법밖에 없어서,
 * 키가 백만 개면 20개를 뽑는 것조차 비싸다.
 *
 * <p>그래서 배열과 맵을 같이 쓴다.
 * <pre>
 *   keys  : [a, b, c, d]             인덱스로 무작위 선택 O(1)
 *   index : {a=0, b=1, c=2, d=3}     지울 키의 위치 찾기 O(1)
 * </pre>
 * 배열 중간을 그냥 지우면 뒤를 전부 한 칸씩 당겨야 해서 O(n) 이다.
 * 순서는 상관없으므로 대신 <b>마지막 원소를 빈자리로 옮기고 끝을 잘라낸다</b>(swap-remove).
 * <pre>
 *   b 삭제:  keys [a, b, c, d] → [a, d, c]     index {a=0, d=1, c=2}
 * </pre>
 */
final class ExpiringKeys {

    private final List<Key> keys = new ArrayList<>();
    private final Map<Key, Integer> index = new HashMap<>();

    /** 이미 있으면 아무 일도 하지 않는다. */
    void add(Key key) {
        if (index.putIfAbsent(key, keys.size()) == null) {
            keys.add(key);
        }
    }

    /** 없으면 아무 일도 하지 않는다. */
    void remove(Key key) {
        Integer position = index.remove(key);
        if (position == null) {
            return;
        }
        Key last = keys.remove(keys.size() - 1);
        // 지운 키가 원래 마지막이었다면 잘라낸 것으로 끝이다. 아니면 마지막 키를 빈자리로 옮긴다.
        if (position < keys.size()) {
            keys.set(position, last);
            index.put(last, position);
        }
    }

    Key random(RandomGenerator random) {
        return keys.get(random.nextInt(keys.size()));
    }

    int size() {
        return keys.size();
    }

    boolean contains(Key key) {
        return index.containsKey(key);
    }
}
