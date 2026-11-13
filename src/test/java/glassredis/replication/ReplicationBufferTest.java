package glassredis.replication;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplicationBufferTest {

    private static final long MB = 1024 * 1024;

    /** 레플리카 흉내 - 블록 참조만 */
    private static final class FakeReplica implements ReplicationBuffer.Ref {
        ReplicationBuffer.Block block;
        int pos;
        boolean feed = true;

        @Override
        public boolean canFeed() {
            return feed;
        }

        @Override
        public ReplicationBuffer.Block refBlock() {
            return block;
        }

        @Override
        public int refPos() {
            return pos;
        }

        @Override
        public void ref(ReplicationBuffer.Block block, int pos) {
            this.block = block;
            this.pos = pos;
        }
    }

    private final List<FakeReplica> replicas = new ArrayList<>();
    private final List<ReplicationBuffer.Ref> newBlockChecks = new ArrayList<>();

    @Test
    @DisplayName("jemalloc 크기 등급 - 16KB+헤더는 20KB, 128 이하는 16 단위")
    void jemallocClasses() {
        assertEquals(8, Jemalloc.usableSize(1));
        assertEquals(48, Jemalloc.usableSize(33));
        assertEquals(160, Jemalloc.usableSize(129));
        assertEquals(16384, Jemalloc.usableSize(16384));
        assertEquals(20480, Jemalloc.usableSize(16384 + 40));
        assertEquals(32768, Jemalloc.usableSize(30000 + 40));
        assertEquals(65536 + 16384, Jemalloc.usableSize(65536 + 40));
    }

    /**
     * Redis 7.2.4 (jemalloc-5.3.0) 에서 레플리카를 붙였다 떼어 백로그만 남기고 SET 을 쓴 뒤 INFO replication 값
     * 값 크기를 바꿔 블록 크기 규칙(16KB 최소, 백로그/16 최대)과 트림 규칙을 모두 지나감
     */
    @Test
    @DisplayName("백로그 첫 바이트·길이가 Redis INFO 와 같음 - 값 100·30000·70000·1000 바이트")
    void trimMatchesRedis() {
        ReplicationBuffer buffer = new ReplicationBuffer(MB, replicas, newBlockChecks::add);
        buffer.createBacklog();
        buffer.feed(command("SELECT", "0"));

        phase(buffer, 50, 100);
        assertState(buffer, 6713, 1, 6713);
        phase(buffer, 40, 30000);
        assertState(buffer, 1208223, 159505, 1048719);
        phase(buffer, 30, 70000);
        assertState(buffer, 3309353, 2201449, 1107905);
        phase(buffer, 200, 1000);
        assertState(buffer, 3516643, 2414321, 1102323);
    }

    @Test
    @DisplayName("레플리카가 첫 블록을 참조하면 백로그가 커져도 자르지 않음")
    void replicaReferenceBlocksTrim() {
        FakeReplica slow = new FakeReplica();
        replicas.add(slow);
        ReplicationBuffer buffer = new ReplicationBuffer(64 * 1024, replicas, newBlockChecks::add);
        buffer.createBacklog();
        for (int i = 0; i < 100; i++) {
            buffer.feed(command("SET", "k" + i, "x".repeat(5000)));
        }
        assertEquals(1, buffer.backlogOffset(), "느린 레플리카가 첫 블록을 잡고 있음");
        assertEquals(buffer.masterReplOffset(), buffer.histlen());

        // 다 보냈다고 알리면 참조가 꼬리로 옮겨 가며 정리
        buffer.advance(slow, buffer.masterReplOffset());
        assertEquals(buffer.masterReplOffset() - buffer.histlen() + 1, buffer.backlogOffset());
        long histlen = buffer.histlen();
        assertTrue(histlen > 64 * 1024 && histlen < 64 * 1024 + 20 * 1024,
                "백로그 크기 이상, 블록 하나 더 안쪽: " + histlen);
    }

    @Test
    @DisplayName("부분 동기화 위치 - 원하는 바이트부터 끝까지 복사")
    void referenceFromAndCopy() {
        ReplicationBuffer buffer = new ReplicationBuffer(MB, replicas, newBlockChecks::add);
        buffer.createBacklog();
        byte[] first = command("SET", "a", "1");
        byte[] second = command("SET", "b", "2");
        buffer.feed(first);
        buffer.feed(second);

        FakeReplica replica = new FakeReplica();
        long length = buffer.referenceFrom(replica, first.length + 1);
        assertEquals(second.length, length);
        assertEquals(first.length, replica.pos);
        assertArrayEquals(second, buffer.copySince(first.length));

        buffer.release(replica);
        assertNull(replica.block);
    }

    @Test
    @DisplayName("WAIT_BGSAVE_START 레플리카는 참조 안 함, 그 뒤엔 첫 피드 위치를 참조")
    void feedSetsReferenceOnlyForFeedableReplicas() {
        FakeReplica waiting = new FakeReplica();
        waiting.feed = false;
        replicas.add(waiting);
        ReplicationBuffer buffer = new ReplicationBuffer(MB, replicas, newBlockChecks::add);
        buffer.createBacklog();
        buffer.feed(command("PING"));
        assertNull(waiting.block);

        waiting.feed = true;
        buffer.feed(command("SET", "a", "1"));
        assertEquals(14, waiting.pos, "PING 14바이트 뒤부터");
        assertEquals(1, waiting.block.replOffset);
    }

    @Test
    @DisplayName("레플리카 출력 버퍼 사용량 - 참조 블록부터 꼬리까지 크기 + 블록당 64바이트")
    void outputBufferUsage() {
        FakeReplica replica = new FakeReplica();
        replicas.add(replica);
        ReplicationBuffer buffer = new ReplicationBuffer(MB, replicas, newBlockChecks::add);
        buffer.createBacklog();
        buffer.feed(command("SET", "a", "1"));
        // 블록 1개 - 20480 - 40 = 20440 바이트 + 노드 64
        assertEquals(20440 + 64, buffer.outputBufferUsage(replica));
        assertEquals(1, newBlockChecks.size(), "새 블록 생길 때 한도 검사");
    }

    private static void phase(ReplicationBuffer buffer, int count, int size) {
        String value = "x".repeat(size);
        for (int i = 0; i < count; i++) {
            buffer.feed(command("SET", "k" + size + "-" + i, value));
        }
    }

    private static void assertState(ReplicationBuffer buffer, long offset, long firstByte, long histlen) {
        assertEquals(offset, buffer.masterReplOffset(), "master_repl_offset");
        assertEquals(firstByte, buffer.backlogOffset(), "repl_backlog_first_byte_offset");
        assertEquals(histlen, buffer.histlen(), "repl_backlog_histlen");
    }

    private static byte[] command(String... argv) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(("*" + argv.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
        for (String arg : argv) {
            byte[] bytes = arg.getBytes(StandardCharsets.US_ASCII);
            out.writeBytes(("$" + bytes.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.writeBytes(bytes);
            out.writeBytes("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        return out.toByteArray();
    }
}
