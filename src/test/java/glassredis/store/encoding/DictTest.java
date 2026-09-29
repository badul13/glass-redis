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
    @DisplayName("초기 크기 4 - 원소 수가 버킷 수에 닿으면 새 테이블 생성")
    void expandsAtOneToOne() {
        Dict<Integer> dict = new Dict<>();
        for (int i = 0; i < 4; i++) {
            dict.add(Key.of("k" + i), i);
        }
        assertEquals(4, dict.tableSize(0));
        assertFalse(dict.isRehashing());

        // 원소 4 = 버킷 4 - 다섯 번째 추가에서 8칸 테이블로 확장
        dict.add(Key.of("k4"), 4);
        assertEquals(8, dict.tableSize(1));
        assertTrue(dict.isRehashing());
    }

    @Test
    @DisplayName("옮기는 중에도 두 테이블을 모두 확인해 모든 키 조회")
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
    @DisplayName("명령마다 조금씩 이전, 완료 시 새 테이블이 ht[0]")
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
    @DisplayName("채움률 10% 미만이면 원소 수에 맞춰 축소")
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
    @DisplayName("무작위 넣기·지우기 결과가 HashMap 과 동일")
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
    @DisplayName("scan - 0 에서 시작해 0 으로 돌아오기까지 모든 키를 한 번 이상 방문")
    void scanVisitsEverything() {
        Dict<Integer> dict = new Dict<>();
        for (int i = 0; i < 500; i++) {
            dict.add(Key.of("k" + i), i);
        }
        Set<String> seen = new HashSet<>();
        long cursor = 0;
        do {
            cursor = dict.scan(cursor, (key, value) -> seen.add(new String(key.bytes())));
        } while (cursor != 0);

        assertEquals(500, seen.size());
    }

    @Test
    @DisplayName("순회 도중 테이블이 커지고 옮겨져도 처음부터 있던 키 누락 없음 - 커서를 거꾸로 세는 이유")
    void scanSurvivesGrowth() {
        Dict<Integer> dict = new Dict<>();
        for (int i = 0; i < 100; i++) {
            dict.add(Key.of("old" + i), i);
        }
        Set<String> seen = new HashSet<>();
        long cursor = 0;
        int added = 0;
        do {
            cursor = dict.scan(cursor, (key, value) -> seen.add(new String(key.bytes())));
            // 스캔 중 테이블 확장·이전을 유도하려고 키 추가 - 끝없이 커지면 스캔이 안 끝나므로 2000개에서 중단
            for (int j = 0; j < 20 && added < 2000; j++) {
                dict.add(Key.of("new" + added++), 0);
            }
        } while (cursor != 0);

        for (int i = 0; i < 100; i++) {
            assertTrue(seen.contains("old" + i), "빠뜨린 키: old" + i);
        }
    }

    @Test
    @DisplayName("scan 의 visitor 가 현재 키를 지워도 안전 - 주기적 만료의 사용 방식")
    void scanAllowsDeletingCurrentKey() {
        Dict<Integer> dict = new Dict<>();
        for (int i = 0; i < 200; i++) {
            dict.add(Key.of("k" + i), i);
        }
        long cursor = 0;
        do {
            cursor = dict.scan(cursor, (key, value) -> dict.remove(key));
        } while (cursor != 0);

        assertEquals(0, dict.size());
    }

    @Test
    @DisplayName("SipHash-1-2 - 같은 씨앗이면 같은 값, 씨앗이 다르면 다른 값")
    void sipHashUsesSeed() {
        byte[] input = "hello".getBytes();
        byte[] seedA = new byte[16];
        byte[] seedB = new byte[16];
        seedB[0] = 1;

        assertEquals(SipHash.hash(input, seedA), SipHash.hash(input, seedA));
        assertFalse(SipHash.hash(input, seedA) == SipHash.hash(input, seedB));
    }
}
