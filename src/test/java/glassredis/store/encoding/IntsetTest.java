package glassredis.store.encoding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntsetTest {

    @Test
    @DisplayName("정렬된 채로 담고, 중복은 받지 않는다")
    void sortedAndUnique() {
        Intset set = new Intset();
        assertTrue(set.add(3));
        assertTrue(set.add(1));
        assertTrue(set.add(2));
        assertFalse(set.add(2));

        assertEquals(3, set.length());
        assertEquals(1, set.get(0));
        assertEquals(3, set.get(2));
    }

    @Test
    @DisplayName("폭에 안 맞는 값이 오면 전체를 넓은 폭으로 다시 쓰고, 지워도 좁아지지 않는다")
    void upgradeIsOneWay() {
        Intset set = new Intset();
        set.add(1);
        set.add(2);
        assertEquals(2, set.encoding());
        assertEquals(8 + 2 * 2, set.bytes());

        set.add(100_000);
        assertEquals(4, set.encoding());
        assertEquals(8 + 3 * 4, set.bytes());

        set.add(-5_000_000_000L);
        assertEquals(8, set.encoding());
        assertEquals(-5_000_000_000L, set.get(0));

        set.remove(-5_000_000_000L);
        set.remove(100_000);
        assertEquals(8, set.encoding());
    }

    @Test
    @DisplayName("바이트 배치: [폭 4B][개수 4B][원소...] 리틀 엔디언")
    void layout() {
        Intset set = new Intset();
        set.add(1);
        set.add(-2);

        assertArrayEquals(new byte[] {2, 0, 0, 0, 2, 0, 0, 0, (byte) 0xFE, (byte) 0xFF, 1, 0}, set.rawBytes());
    }

    @Test
    @DisplayName("무작위로 넣고 빼도 TreeSet 과 같다")
    void matchesReference() {
        SplittableRandom random = new SplittableRandom(3);
        Intset set = new Intset();
        TreeSet<Long> reference = new TreeSet<>();
        for (int i = 0; i < 5000; i++) {
            long value = switch (random.nextInt(3)) {
                case 0 -> random.nextInt(-100, 100);
                case 1 -> random.nextInt();
                default -> random.nextLong();
            };
            if (random.nextInt(3) == 0) {
                assertEquals(reference.remove(value), set.remove(value));
            } else {
                assertEquals(reference.add(value), set.add(value));
            }
        }
        assertEquals(reference.size(), set.length());
        int i = 0;
        for (long value : reference) {
            assertEquals(value, set.get(i++));
            assertTrue(set.contains(value));
        }
    }
}
