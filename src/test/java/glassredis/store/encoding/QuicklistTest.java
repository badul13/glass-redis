package glassredis.store.encoding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** quicklist.c 규칙 - list-max-listpack-size -2 (노드당 8KB) 기준 */
class QuicklistTest {

    @Test
    @DisplayName("8KB 경계 - 현재 바이트 + 값 길이 + 8 이 8192 이하면 같은 노드 (_quicklistNodeAllowInsert)")
    void nodeSplitsAtEightKilobytes() {
        // 100바이트 문자열 원소 = 머리 2 + 100 + backlen 1 = 103바이트, 빈 listpack 7바이트
        // k개 있을 때 7 + 103k + 100 + 8 <= 8192 → k <= 78 이면 허용 → 노드당 79개
        Quicklist ql = new Quicklist();
        for (int i = 0; i < 79; i++) {
            ql.pushTail(value(100, i));
        }
        assertEquals(1, ql.nodeCount());
        assertEquals(7 + 103 * 79, ql.head().listpack().bytes());

        ql.pushTail(value(100, 79));
        assertEquals(2, ql.nodeCount(), "80번째는 새 노드");
        assertEquals(79, ql.head().listpack().length());
        assertEquals(1, ql.head().next().listpack().length());

        // 앞쪽 넣기도 같은 규칙 - 꽉 찬 머리 노드 앞에 새 노드
        ql.pushHead(value(100, -1));
        assertEquals(3, ql.nodeCount());
        assertEquals(1, ql.head().listpack().length());
    }

    @Test
    @DisplayName("경계 정확히 - 합이 8192 면 허용, 8193 이면 새 노드")
    void exactBoundary() {
        Quicklist ql = new Quicklist();
        ql.pushTail(value(4000, 0));   // 7 + (2 + 4000 + 2) = 4011
        // 남은 값 길이 x: 4011 + x + 8 <= 8192 → x <= 4173
        ql.pushTail(value(4173, 1));
        assertEquals(1, ql.nodeCount(), "4011 + 4173 + 8 = 8192");

        Quicklist other = new Quicklist();
        other.pushTail(value(4000, 0));
        other.pushTail(value(4174, 1));
        assertEquals(2, other.nodeCount(), "4011 + 4174 + 8 = 8193");
    }

    @Test
    @DisplayName("removeMatching 역방향 - 뒤 노드부터 여러 노드에 걸쳐 limit 개만, 빈 노드는 분리")
    void removeMatchingBackwardsAcrossNodes() {
        Quicklist ql = new Quicklist();
        List<String> reference = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            String v = i % 3 == 0 ? "x".repeat(100) : "v" + i + "-".repeat(100);
            ql.pushTail(bytes(v));
            reference.add(v);
        }
        assertTrue(ql.nodeCount() >= 4);

        // 뒤에서 70개 - 마지막 210개 구간의 x 70개
        assertEquals(70, ql.removeMatching(bytes("x".repeat(100)), 70, true));
        int removed = 0;
        for (int i = reference.size() - 1; i >= 0 && removed < 70; i--) {
            if (reference.get(i).equals("x".repeat(100))) {
                reference.remove(i);
                removed++;
            }
        }
        assertEquals(reference, strings(ql));
        assertEquals(reference.size(), ql.count());
        assertNodesConsistent(ql);
    }

    @Test
    @DisplayName("removeMatching 으로 한 노드가 통째로 비면 노드 분리")
    void removeMatchingUnlinksEmptiedNode() {
        Quicklist ql = new Quicklist();
        for (int i = 0; i < 79; i++) {
            ql.pushTail(value(100, 7));
        }
        String keep = "keep" + "k".repeat(96);
        ql.pushTail(bytes(keep));
        assertEquals(2, ql.nodeCount());

        assertEquals(79, ql.removeMatching(value(100, 7), Long.MAX_VALUE, false));
        assertEquals(1, ql.nodeCount());
        assertEquals(List.of(keep), strings(ql));
    }

    @Test
    @DisplayName("deleteRange - 노드 가운데부터 여러 노드에 걸쳐, 통째인 노드는 분리 (quicklistDelRange)")
    void deleteRangeAcrossNodes() {
        Quicklist ql = new Quicklist();
        List<String> reference = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            String v = new String(value(100, i), StandardCharsets.US_ASCII);
            ql.pushTail(bytes(v));
            reference.add(v);
        }
        int nodesBefore = ql.nodeCount();

        ql.deleteRange(50, 200);
        reference.subList(50, 250).clear();

        assertEquals(reference, strings(ql));
        assertEquals(200, ql.count());
        assertTrue(ql.nodeCount() < nodesBefore, "통째로 지운 노드 분리");
        assertNodesConsistent(ql);

        // 끝을 넘는 개수 - 끝까지만
        ql.deleteRange(190, 1000);
        assertEquals(190, ql.count());
        assertEquals(reference.subList(0, 190), strings(ql));
    }

    @Test
    @DisplayName("뒤쪽 절반 인덱스는 꼬리 노드부터 걷기 - 양수·음수 인덱스 모두 정답과 같음")
    void indexFromTail() {
        Quicklist ql = new Quicklist();
        List<String> reference = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            String v = "e" + i + "-".repeat(i % 200);
            ql.pushTail(bytes(v));
            reference.add(v);
        }
        for (int i = 0; i < reference.size(); i += 7) {
            assertEquals(reference.get(i), text(ql.index(i)), "인덱스 " + i);
            assertEquals(reference.get(reference.size() - 1 - i), text(ql.index(-1 - i)), "인덱스 " + (-1 - i));
        }
        assertNull(ql.index(500));
        assertNull(ql.index(-501));
        assertEquals(reference.subList(480, 500), texts(ql.range(480, 499)));
    }

    @Test
    @DisplayName("대량 무작위 - 양끝 넣기·빼기, LREM 앞뒤, LTRIM 식 범위 삭제, LINDEX 결과가 정답과 동일")
    void matchesReferenceUnderRandomOperations() {
        SplittableRandom random = new SplittableRandom(11);
        Quicklist ql = new Quicklist();
        List<String> reference = new ArrayList<>();
        for (int step = 0; step < 6000; step++) {
            String v = random.nextInt(4) == 0 ? "dup" : random.nextBoolean()
                    ? Long.toString(random.nextLong(-1_000_000, 1_000_000))
                    : "s".repeat(random.nextInt(0, 600));
            switch (random.nextInt(7)) {
                case 0, 1 -> {
                    ql.pushTail(bytes(v));
                    reference.add(v);
                }
                case 2 -> {
                    ql.pushHead(bytes(v));
                    reference.add(0, v);
                }
                case 3 -> {
                    boolean head = random.nextBoolean();
                    byte[] popped = ql.pop(head);
                    if (reference.isEmpty()) {
                        assertNull(popped);
                    } else {
                        assertEquals(head ? reference.remove(0) : reference.remove(reference.size() - 1),
                                text(popped));
                    }
                }
                case 4 -> {
                    boolean fromTail = random.nextBoolean();
                    int limit = random.nextInt(1, 4);
                    long removed = ql.removeMatching(bytes("dup"), limit, fromTail);
                    int expected = 0;
                    if (fromTail) {
                        for (int i = reference.size() - 1; i >= 0 && expected < limit; i--) {
                            if (reference.get(i).equals("dup")) {
                                reference.remove(i);
                                expected++;
                            }
                        }
                    } else {
                        for (int i = 0; i < reference.size() && expected < limit; ) {
                            if (reference.get(i).equals("dup")) {
                                reference.remove(i);
                                expected++;
                            } else {
                                i++;
                            }
                        }
                    }
                    assertEquals(expected, removed);
                }
                case 5 -> {
                    if (!reference.isEmpty()) {
                        int start = random.nextInt(reference.size());
                        int num = random.nextInt(1, 40);
                        ql.deleteRange(start, num);
                        reference.subList(start, Math.min(reference.size(), start + num)).clear();
                    }
                }
                default -> {
                    if (!reference.isEmpty()) {
                        int i = random.nextInt(reference.size());
                        assertEquals(reference.get(i), text(ql.index(i)));
                        assertEquals(reference.get(i), text(ql.index(i - reference.size())));
                    }
                }
            }
            if (step % 500 == 0) {
                assertEquals(reference, strings(ql));
                assertNodesConsistent(ql);
            }
        }
        assertEquals(reference, strings(ql));
        assertEquals(reference.size(), ql.count());
        assertNodesConsistent(ql);
    }

    /** 빈 노드 없음, 노드 수와 원소 수 일치, 원소 둘 이상인 노드는 8KB 이하 */
    private static void assertNodesConsistent(Quicklist ql) {
        int nodes = 0;
        long elements = 0;
        for (Quicklist.Node node = ql.head(); node != null; node = node.next()) {
            int n = node.listpack().length();
            assertTrue(n > 0, "빈 노드");
            if (n > 1) {
                assertTrue(node.listpack().bytes() <= Quicklist.NODE_SIZE_LIMIT, "8KB 초과 노드");
            }
            nodes++;
            elements += n;
        }
        assertEquals(ql.nodeCount(), nodes);
        assertEquals(ql.count(), elements);
    }

    private static byte[] value(int length, int tag) {
        byte[] v = new byte[length];
        Arrays.fill(v, (byte) 'a');
        byte[] digits = Integer.toString(tag).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(digits, 0, v, 0, Math.min(digits.length, length));
        return v;
    }

    private static List<String> strings(Quicklist ql) {
        return ql.count() == 0 ? List.of() : texts(ql.range(0, ql.count() - 1));
    }

    private static List<String> texts(List<byte[]> values) {
        List<String> out = new ArrayList<>();
        values.forEach(v -> out.add(text(v)));
        return out;
    }

    private static String text(byte[] v) {
        return v == null ? null : new String(v, StandardCharsets.US_ASCII);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }
}
