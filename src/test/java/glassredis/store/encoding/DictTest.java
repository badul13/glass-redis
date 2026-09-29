package glassredis.store.encoding;

import glassredis.store.Key;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DictTest {

    @Test
    @DisplayName("처음 크기는 4이고, 원소 수가 버킷 수에 닿으면 새 테이블을 만든다")
    void expandsAtOneToOne() {
        Dict<Integer> dict = new Dict<>();
        for (int i = 0; i < 4; i++) {
            dict.add(Key.of("k" + i), i);
        }
        assertEquals(4, dict.tableSize(0));
        assertFalse(dict.isRehashing());

        // 다섯 번째를 넣는 순간 원소 4 = 버킷 4 라서 8칸짜리 새 테이블을 만든다.
        dict.add(Key.of("k4"), 4);
        assertEquals(8, dict.tableSize(1));
        assertTrue(dict.isRehashing());
    }

    @Test
    @DisplayName("옮기는 중에도 두 테이블을 다 봐서 모든 키를 찾는다")
    void findsDuringRehash() {
        Dict<Integer> dict = new Dict<>();
        for (int i = 0; i < 5; i++) {
            dict.add(Key.of("k" + i), i);
        }
        assertTrue(dict.isRehashing());
        for (int i = 0; i < 5; i++) {
            assertEquals(i, dict.get(Key.of("k" + i)));
        }
    }

    @Test
    @DisplayName("명령이 올 때마다 조금씩 옮기다가, 다 옮기면 새 테이블이 ht[0] 이 된다")
    void rehashFinishesStepByStep() {
        Dict<Integer> dict = new Dict<>();
        for (int i = 0; i < 5; i++) {
            dict.add(Key.of("k" + i), i);
        }
        int steps = 0;
        while (dict.isRehashing()) {
            dict.get(Key.of("anything"));
            steps++;
        }
        assertTrue(steps > 0);
        assertEquals(8, dict.tableSize(0));
        assertEquals(0, dict.tableSize(1));
        assertEquals(5, dict.tableUsed(0));
    }

    @Test
    @DisplayName("채움률이 10% 아래로 떨어지면 원소 수에 맞춰 줄인다")
    void shrinks() {
        Dict<Integer> dict = new Dict<>();
        for (int i = 0; i < 100; i++) {
            dict.add(Key.of("k" + i), i);
        }
        while (dict.isRehashing()) {
            dict.get(Key.of("x"));
        }
        assertEquals(128, dict.tableSize(0));

        for (int i = 0; i < 95; i++) {
            dict.remove(Key.of("k" + i));
            dict.shrinkIfNeeded();
        }
        while (dict.isRehashing()) {
            dict.get(Key.of("x"));
        }
        assertTrue(dict.tableSize(0) <= 16, "줄어든 크기: " + dict.tableSize(0));
        for (int i = 95; i < 100; i++) {
            assertEquals(i, dict.get(Key.of("k" + i)));
        }
    }

    @Test
    @DisplayName("무작위 넣기·지우기가 HashMap 과 같은 결과를 낸다")
    void matchesReference() {
        SplittableRandom random = new SplittableRandom(11);
        Dict<Integer> dict = new Dict<>();
        Map<String, Integer> reference = new HashMap<>();
        for (int i = 0; i < 20_000; i++) {
            String key = "k" + random.nextInt(3000);
            if (random.nextInt(3) == 0) {
                assertEquals(reference.remove(key) != null, dict.remove(Key.of(key)));
                dict.shrinkIfNeeded();
            } else {
                assertEquals(!reference.containsKey(key), dict.put(Key.of(key), i));
                reference.put(key, i);
            }
        }
        assertEquals(reference.size(), dict.size());
        Set<String> seen = new HashSet<>();
        dict.forEach((key, value) -> {
            String text = new String(key.bytes());
            assertTrue(seen.add(text), "두 번 나온 키: " + text);
            assertEquals(reference.get(text), value);
        });
        assertEquals(reference.keySet(), seen);
    }

    @Test
    @DisplayName("SipHash-1-2 는 같은 씨앗이면 같은 값, 씨앗이 다르면 다른 값을 낸다")
    void sipHashUsesSeed() {
        byte[] input = "hello".getBytes();
        byte[] seedA = new byte[16];
        byte[] seedB = new byte[16];
        seedB[0] = 1;

        assertEquals(SipHash.hash(input, seedA), SipHash.hash(input, seedA));
        assertFalse(SipHash.hash(input, seedA) == SipHash.hash(input, seedB));
    }
}
