package glassredis.store.encoding;

import glassredis.store.Key;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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

    @Test
    @DisplayName("배열 한도(2^30 버킷)를 넘는 확장은 거절 - 2^31 은 음수 길이, 2^32 는 길이 1 배열이 되던 결함")
    void expandBeyondArrayLimitIsRefused() {
        Dict<Integer> dict = new Dict<>();

        assertDoesNotThrow(() -> dict.expand(1L << 31));
        assertEquals(0, dict.slots(), "2^31 요청 후 테이블");
        assertDoesNotThrow(() -> dict.expand(3_000_000_000L));
        assertEquals(0, dict.slots(), "30억 요청 후 테이블");

        // 거절 뒤에도 정상 사용
        dict.add(Key.of("k"), 1);
        assertEquals(1, dict.get(Key.of("k")));
        assertEquals(Dict.INITIAL_SIZE, dict.slots());
    }

    @Test
    @DisplayName("축소 rehash - 해시 재계산 없이 버킷 번호 마스킹, 끝나면 작은 테이블이 ht[0]")
    void shrinkingRehashKeepsEveryKey() {
        Dict<Integer> dict = settled(200);
        assertEquals(256, dict.tableSize(0));
        for (int i = 0; i < 190; i++) {
            dict.remove(Key.of("k" + i));
        }
        assertTrue(dict.needsResize());
        dict.shrinkIfNeeded();
        assertTrue(dict.isRehashing());
        assertEquals(16, dict.tableSize(1), "남은 10개를 담는 최소 크기");

        while (dict.isRehashing()) {
            dict.get(Key.of("x"));
        }
        assertEquals(16, dict.tableSize(0));
        for (int i = 190; i < 200; i++) {
            assertEquals(i, dict.get(Key.of("k" + i)));
        }
    }

    @Test
    @DisplayName("축소 중 scan - 큰 쪽 ht[0] 와 작은 쪽 ht[1] 을 함께 돌아 처음부터 있던 키 전부 방문")
    void scanDuringShrinkVisitsEverything() {
        Dict<Integer> dict = settled(300);
        for (int i = 0; i < 280; i++) {
            dict.remove(Key.of("k" + i));
        }
        dict.shrinkIfNeeded();
        // 일부만 옮긴 상태에서 순회 시작
        dict.rehash(3);
        assertTrue(dict.isRehashing());
        assertTrue(dict.tableSize(0) > dict.tableSize(1), "축소 중");

        Set<String> seen = new HashSet<>();
        long cursor = 0;
        do {
            cursor = dict.scan(cursor, (key, value) -> seen.add(new String(key.bytes())));
            // 순회 사이사이 rehash 진행 - 커서가 크기 변화를 견디는지
            dict.get(Key.of("x"));
        } while (cursor != 0);

        for (int i = 280; i < 300; i++) {
            assertTrue(seen.contains("k" + i), "놓친 키 k" + i);
        }
    }

    @Test
    @DisplayName("빈 버킷은 한 단계에 n×10 개까지만 확인 - 넘으면 키를 못 옮겨도 남은 작업 있음 반환")
    void rehashStopsAfterTenEmptyBucketsPerStep() {
        Dict<Integer> dict = settled(1000);
        assertEquals(1024, dict.tableSize(0));
        for (int i = 0; i < 1000; i++) {
            dict.remove(Key.of("k" + i));
        }
        // 버킷 번호 25 이상인 키 하나 - 앞의 빈 버킷 25개를 지나야 닿음
        Key far = null;
        for (int n = 0; far == null; n++) {
            Key candidate = Key.of("far" + n);
            if ((SipHash.hash(candidate.bytes()) & 1023) >= 25) {
                far = candidate;
            }
        }
        dict.add(far, 1);
        dict.expand(2048);
        assertTrue(dict.isRehashing());

        assertTrue(dict.rehash(1), "빈 칸 10개 확인 후 중단");
        assertTrue(dict.rehash(1), "빈 칸 20개째 확인 후 중단");
        assertEquals(1, dict.tableUsed(0), "아직 못 옮긴 키");

        while (dict.rehash(1)) {
            // 키에 닿을 때까지
        }
        assertEquals(1, dict.get(far));
        assertEquals(2048, dict.tableSize(0));
    }

    @Test
    @DisplayName("peek 은 rehash 를 진행시키지 않음 - 대시보드 조회가 테이블 모양을 바꾸지 않도록")
    void peekDoesNotAdvanceRehash() {
        Dict<Integer> dict = filled(5);
        assertTrue(dict.isRehashing());
        long before = dict.tableUsed(0);

        for (int i = 0; i < 50; i++) {
            assertEquals(1, dict.peek(Key.of("k1")));
        }

        assertTrue(dict.isRehashing());
        assertEquals(before, dict.tableUsed(0));
    }

    @Test
    @DisplayName("scan 중에는 rehash 정지 - visitor 안의 조회도 버킷을 옮기지 않음 (pauserehash)")
    void scanPausesRehash() {
        Dict<Integer> dict = filled(5);
        assertTrue(dict.isRehashing());
        long before = dict.tableUsed(0);

        dict.scan(0, (key, value) -> {
            dict.get(Key.of("k0"));
            assertEquals(before, dict.tableUsed(0), "scan 도중 이동");
        });
    }

    @Test
    @DisplayName("dictTryExpand - 할당 불가만 실패, 같은 크기·rehash 중이라 건너뛴 건 성공 / dictExpand 는 실제 확장 여부")
    void tryExpandReportsOnlyAllocationFailure() {
        Dict<Integer> dict = filled(3);
        assertFalse(dict.expand(4), "같은 크기 - 건너뜀");
        assertTrue(dict.tryExpand(4), "건너뜀은 실패 아님");
        assertFalse(dict.tryExpand(3_000_000_000L), "2^32 버킷 - 할당 불가");
        assertFalse(dict.tryExpand(1L << 31));
        assertTrue(dict.expand(100));
        assertTrue(dict.isRehashing());
        assertFalse(dict.expand(1000), "rehash 중 - 건너뜀");
    }

    @Test
    @DisplayName("연산마다 rehash 기회 한 번 - 찾고 넣기도 dictAddRaw 한 번 (dictReplace), peek·빈 dict 삭제는 0")
    void oneRehashStepPerOperation() {
        Dict<Integer> dict = settled(300);
        dict.expand(1024);
        assertTrue(dict.isRehashing());

        assertSteps(dict, 1, () -> dict.put(Key.of("new"), 1));
        assertSteps(dict, 1, () -> dict.put(Key.of("k1"), 100));
        assertSteps(dict, 1, () -> dict.putIfAbsent(Key.of("k2"), 7));
        assertSteps(dict, 1, () -> dict.add(Key.of("k3"), 7));
        assertSteps(dict, 1, () -> dict.get(Key.of("k4")));
        assertSteps(dict, 1, () -> dict.find(Key.of("k4")));
        assertSteps(dict, 1, () -> dict.remove(Key.of("k5")));
        assertSteps(dict, 1, () -> dict.unlink(Key.of("k6")));
        assertSteps(dict, 0, () -> dict.peek(Key.of("k7")));
        assertEquals(100, dict.get(Key.of("k1")), "dictReplace 로 값 교체");
        assertEquals(2, dict.putIfAbsent(Key.of("k2"), 9), "있으면 기존 값 그대로");

        Dict<Integer> empty = new Dict<>();
        assertSteps(empty, 0, () -> empty.remove(Key.of("x")));
    }

    @Test
    @DisplayName("AVOID(자식 작업 중) - 확장은 원소/버킷 비 5 초과 때만, rehash 는 크기비 5 미만이면 정지, 축소 거절")
    void avoidPolicyRestrictsResizing() {
        try {
            Dict<Integer> grow = new Dict<>();
            Dict.setResizePolicy(Dict.ResizePolicy.AVOID);
            for (int i = 0; i < 24; i++) {
                grow.add(Key.of("g" + i), i);
            }
            assertEquals(4, grow.slots(), "24개 - 비 6 이 되기 전이라 확장 없음");
            grow.add(Key.of("g24"), 24);
            assertEquals(4 + 32, grow.slots(), "25번째 - 24/4 = 6 > 5 라 25 이상 크기로 확장");
            // 크기비 8 - AVOID 중에도 rehash 진행
            long before = grow.tableUsed(0);
            grow.get(Key.of("g0"));
            assertTrue(grow.tableUsed(0) < before, "크기비 5 이상이면 진행");

            Dict.setResizePolicy(Dict.ResizePolicy.ENABLE);
            Dict<Integer> dict = settled(100);
            dict.expand(256);
            Dict.setResizePolicy(Dict.ResizePolicy.AVOID);
            long used0 = dict.tableUsed(0);
            dict.get(Key.of("k1"));
            assertEquals(0, dict.rehashMilliseconds(5), "크기비 2 - 정지");
            assertEquals(used0, dict.tableUsed(0), "AVOID 중 이동 없음");

            Dict.setResizePolicy(Dict.ResizePolicy.ENABLE);
            Dict<Integer> sparse = settled(200);
            for (int i = 0; i < 195; i++) {
                sparse.remove(Key.of("k" + i));
            }
            Dict.setResizePolicy(Dict.ResizePolicy.AVOID);
            assertTrue(sparse.needsResize());
            assertFalse(sparse.resize(), "AVOID 중 dictResize 거절");
            assertFalse(sparse.isRehashing());

            Dict.setResizePolicy(Dict.ResizePolicy.ENABLE);
            assertTrue(sparse.resize());
        } finally {
            Dict.setResizePolicy(Dict.ResizePolicy.ENABLE);
        }
    }

    @Test
    @DisplayName("SipHash - 라운드 수를 2-4 로 두면 siphash.c 의 참조 벡터 64개와 일치 (키 00..0f, 입력 00..len-1)")
    void sipHashReferenceVectors() {
        String[] vectors = {
                "310e0edd47db6f72", "fd67dc93c539f874", "5a4fa9d909806c0d", "2d7efbd796666785",
                "b7877127e09427cf", "8da699cd64557618", "cee3fe586e46c9cb", "37d1018bf50002ab",
                "6224939a79f5f593", "b0e4a90bdf82009e", "f3b9dd94c5bb5d7a", "a7ad6b22462fb3f4",
                "fbe50e86bc8f1e75", "903d84c02756ea14", "eef27a8e90ca23f7", "e545be4961ca29a1",
                "db9bc2577fcc2a3f", "9447be2cf5e99a69", "9cd38d96f0b3c14b", "bd6179a71dc96dbb",
                "98eea21af25cd6be", "c7673b2eb0cbf2d0", "883ea3e395675393", "c8ce5ccd8c030ca8",
                "94af49f6c650adb8", "eab8858ade92e1bc", "f315bb5bb835d817", "adcf6b0763612e2f",
                "a5c91da7acaa4dde", "716595876650a2a6", "28ef495c53a387ad", "42c341d8fa92d832",
                "ce7cf2722f512771", "e37859f94623f3a7", "381205bb1ab0e012", "ae97a10fd434e015",
                "b4a31508beff4d31", "81396229f0907902", "4d0cf49ee5d4dcca", "5c73336a76d8bf9a",
                "d0a704536ba93e0e", "925958fcd6420cad", "a915c29bc8067318", "952b79f3bc0aa6d4",
                "f21df2e41d4535f9", "87577519048f53a9", "10a56cf5dfcd9adb", "eb75095ccd986cd0",
                "51a9cb9ecba312e6", "96afadfc2ce666c7", "72fe52975a4364ee", "5a1645b276d592a1",
                "b274cb8ebf87870a", "6f9bb4203de7b381", "eaecb2a30b22a87f", "9924a43cc1315724",
                "bd838d3aafbf8db7", "0b1a2a3265d51aea", "135079a3231ce660", "932b2846e4d70666",
                "e1915f5cb1eca46c", "f325965ca16d629f", "575ff28e60381be5", "724506eb4c328a95"};
        byte[] key = new byte[16];
        for (int i = 0; i < 16; i++) {
            key[i] = (byte) i;
        }
        for (int len = 0; len < 64; len++) {
            byte[] in = new byte[len];
            for (int i = 0; i < len; i++) {
                in[i] = (byte) i;
            }
            // 벡터는 해시값의 메모리 바이트(리틀 엔디언) 순
            byte[] expected = HexFormat.of().parseHex(vectors[len]);
            long expectedHash = ByteBuffer.wrap(expected).order(ByteOrder.LITTLE_ENDIAN).getLong();
            assertEquals(expectedHash, SipHash.hash(in, key, 2, 4), "길이 " + len);
        }
        // Redis 가 쓰는 1-2 는 같은 몸체에 라운드 수만 다름
        assertEquals(SipHash.hash(new byte[] {1, 2, 3}, key), SipHash.hash(new byte[] {1, 2, 3}, key, 1, 2));
    }

    private static void assertSteps(Dict<?> dict, long expected, Runnable operation) {
        long before = dict.rehashSteps();
        operation.run();
        assertEquals(expected, dict.rehashSteps() - before, "rehash 기회 횟수");
    }

    /** n개를 넣고 진행 중이던 rehash 까지 마친 dict */
    private static Dict<Integer> settled(int n) {
        Dict<Integer> dict = filled(n);
        while (dict.isRehashing()) {
            dict.get(Key.of("x"));
        }
        return dict;
    }

    private static Dict<Integer> filled(int n) {
        Dict<Integer> dict = new Dict<>();
        for (int i = 0; i < n; i++) {
            dict.add(Key.of("k" + i), i);
        }
        return dict;
    }
}
