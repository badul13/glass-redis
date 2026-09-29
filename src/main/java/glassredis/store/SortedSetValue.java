package glassredis.store;

import java.util.HashMap;
import java.util.Map;

/**
 * Sorted Set. 멤버마다 점수가 붙어 있고, 점수 순으로 정렬돼 있다.
 *
 * <p>같은 데이터를 두 구조에 나눠 담는다. 실제 Redis 와 같은 구성이다.
 * <ul>
 *   <li>해시(멤버 → 점수) — "이 멤버의 점수는?"({@code ZSCORE})을 O(1) 로</li>
 *   <li>{@link SkipList} — "순서대로 몇 번째부터 몇 개"({@code ZRANGE})와 순위({@code ZRANK})를 O(log n) 으로</li>
 * </ul>
 * 둘 중 하나만으로는 두 질문 모두에 빨리 답할 수 없다. 대신 넣고 뺄 때 두 곳을 항상 같이 고쳐야 한다.
 * 그래서 둘을 이 클래스 안에 가두고, 고치는 길을 {@link #put}, {@link #remove} 둘로만 열어둔다.
 */
public final class SortedSetValue implements Value {

    private final Map<Key, Double> scores = new HashMap<>();
    private final SkipList order = new SkipList();

    /** 없으면 {@code null}. */
    public Double score(Key member) {
        return scores.get(member);
    }

    /**
     * 넣거나 점수를 바꾼다. 새 멤버였으면 {@code true}.
     *
     * <p>스킵 리스트의 노드는 점수를 바꿀 수 없어서, 점수가 바뀌면 지웠다가 제자리를 새로 찾아 넣는다.
     */
    public boolean put(Key member, double score) {
        Double previous = scores.put(member, score);
        if (previous != null) {
            if (previous == score) {
                return false;
            }
            order.delete(previous, member.bytes());
        }
        order.insert(score, member.bytes());
        return previous == null;
    }

    /** 뺐으면 {@code true}. 비워둔 채로 두면 안 된다 — 빈 Sorted Set 은 키째로 지운다. */
    public boolean remove(Key member) {
        Double score = scores.remove(member);
        if (score == null) {
            return false;
        }
        order.delete(score, member.bytes());
        return true;
    }

    /** 0부터 세는 순위. 없으면 -1. */
    public long rank(Key member) {
        Double score = scores.get(member);
        return score == null ? -1 : order.rank(score, member.bytes()) - 1;
    }

    /** 순서대로 훑을 때 쓴다. 읽기만 한다 — 고치는 건 {@link #put}, {@link #remove} 로만. */
    public SkipList order() {
        return order;
    }

    @Override
    public String typeName() {
        return "zset";
    }

    @Override
    public int size() {
        return scores.size();
    }
}
