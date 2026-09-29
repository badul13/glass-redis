package glassredis.store;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * List. 양 끝에서 넣고 빼는 게 O(1) 인 덱.
 *
 * <p>{@code ArrayDeque} 는 원형 배열이라 앞뒤 어느 쪽으로 넣어도 원소를 밀어낼 일이 없다.
 * 대신 가운데 원소에 인덱스로 바로 갈 수는 없어서 {@code LINDEX}, {@code LRANGE} 는 훑어가야 한다.
 * 실제 Redis 의 List(quicklist, 작은 배열을 이어 붙인 연결 리스트)도 같은 명령이 O(n) 이다.
 * List 는 큐로 쓰라고 있는 자료형이지 가운데를 뒤지라고 있는 게 아니다.
 */
public final class ListValue implements Value {

    private final Deque<byte[]> elements = new ArrayDeque<>();

    /** 제자리에서 고쳐 쓴다. 비워둔 채로 두면 안 된다 — 빈 List 는 키째로 지운다. */
    public Deque<byte[]> elements() {
        return elements;
    }

    @Override
    public String typeName() {
        return "list";
    }

    @Override
    public int size() {
        return elements.size();
    }
}
