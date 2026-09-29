package glassredis.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkipListTest {

    /** 대조용 정답 모델 */
    private record Item(double score, byte[] member) {
    }

    private static final Comparator<Item> ORDER = Comparator.comparingDouble(Item::score)
            .thenComparing(Item::member, Arrays::compareUnsigned);

    private final SkipList list = new SkipList();

    @Test
    @DisplayName("점수 순 정렬, 점수가 같으면 멤버 사전순")
    void orderByScoreThenMember() {
        list.insert(2, bytes("b"));
        list.insert(1, bytes("z"));
        list.insert(2, bytes("a"));

        assertEquals(List.of("z", "a", "b"), membersForward());
        assertEquals(List.of("b", "a", "z"), membersBackward());
    }

    @Test
    @DisplayName("rank 와 byRank 는 1부터 시작, 없으면 0 과 null")
    void rankAndByRank() {
        list.insert(10, bytes("a"));
        list.insert(20, bytes("b"));
        list.insert(30, bytes("c"));

        assertEquals(2, list.rank(20, bytes("b")));
        assertEquals(0, list.rank(20, bytes("nope")));
        assertArrayEquals(bytes("c"), list.byRank(3).member());
        assertNull(list.byRank(0));
        assertNull(list.byRank(4));
    }

    @Test
    @DisplayName("delete - 점수와 멤버가 둘 다 맞을 때만 삭제")
    void deleteNeedsExactScore() {
        list.insert(1, bytes("a"));

        assertFalse(list.delete(2, bytes("a")));
        assertTrue(list.delete(1, bytes("a")));
        assertEquals(0, list.length());
        assertNull(list.first());
        assertNull(list.last());
    }

    @Test
    @DisplayName("점수 구간의 첫 노드와 마지막 노드 탐색")
    void scoreRangeEnds() {
        for (int i = 1; i <= 5; i++) {
            list.insert(i, bytes("m" + i));
        }

        ScoreRange inclusive = new ScoreRange(2, false, 4, false);
        assertEquals(2, list.firstInRange(inclusive).score());
        assertEquals(4, list.lastInRange(inclusive).score());

        ScoreRange exclusive = new ScoreRange(2, true, 4, true);
        assertEquals(3, list.firstInRange(exclusive).score());
        assertEquals(3, list.lastInRange(exclusive).score());

        assertNull(list.firstInRange(new ScoreRange(6, false, 9, false)));
        assertNull(list.lastInRange(new ScoreRange(3, true, 3, false)));
    }

    /** 층수가 무작위 - 손으로 짠 예제만으로는 span 오류 검출 한계 */
    @Test
    @DisplayName("무작위 넣기·빼기 후에도 순위와 순서가 정답과 동일")
    void matchesReferenceUnderRandomOperations() {
        SplittableRandom random = new SplittableRandom(42);
        TreeSet<Item> reference = new TreeSet<>(ORDER);
        List<Item> present = new ArrayList<>();

        for (int step = 0; step < 5_000; step++) {
            if (present.isEmpty() || random.nextInt(3) != 0) {
                // 같은 점수가 자주 나오도록 범위를 좁혀 멤버 비교 경로까지 검증
                Item item = new Item(random.nextInt(50), bytes("m" + random.nextInt(100_000)));
                if (reference.add(item)) {
                    list.insert(item.score(), item.member());
                    present.add(item);
                }
            } else {
                Item item = present.remove(random.nextInt(present.size()));
                reference.remove(item);
                assertTrue(list.delete(item.score(), item.member()));
            }

            if (step % 250 == 0) {
                assertMatches(reference);
            }
        }
        assertMatches(reference);
        assertTrue(list.level() <= SkipList.MAX_LEVEL);
    }

    private void assertMatches(TreeSet<Item> reference) {
        assertEquals(reference.size(), list.length());
        long rank = 1;
        SkipList.Node node = list.first();
        for (Item expected : reference) {
            assertArrayEquals(expected.member(), node.member());
            assertEquals(rank, list.rank(expected.score(), expected.member()));
            assertArrayEquals(expected.member(), list.byRank(rank).member());
            node = node.next();
            rank++;
        }
        assertNull(node);
        if (!reference.isEmpty()) {
            assertArrayEquals(reference.last().member(), list.last().member());
        }
    }

    private List<String> membersForward() {
        List<String> members = new ArrayList<>();
        for (SkipList.Node node = list.first(); node != null; node = node.next()) {
            members.add(new String(node.member(), StandardCharsets.UTF_8));
        }
        return members;
    }

    private List<String> membersBackward() {
        List<String> members = new ArrayList<>();
        for (SkipList.Node node = list.last(); node != null; node = node.previous()) {
            members.add(new String(node.member(), StandardCharsets.UTF_8));
        }
        return members;
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
