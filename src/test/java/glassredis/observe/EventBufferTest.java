package glassredis.observe;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@Timeout(10)
class EventBufferTest {

    @Test
    @DisplayName("담은 순서대로 꺼내 가고, 꺼내 가면 버퍼가 빈다")
    void drainsInOrder() {
        EventBuffer buffer = new EventBuffer(10);
        buffer.offer(record(1));
        buffer.offer(record(2));

        assertEquals(List.of(1L, 2L), sequences(buffer.drain(10)));
        assertEquals(0, buffer.size());
        assertEquals(List.of(), buffer.drain(10));
    }

    @Test
    @DisplayName("가득 차면 가장 오래된 것부터 버린다 — 실시간 화면에서 밀린 줄은 이미 가치가 없다")
    void dropsOldestWhenFull() {
        EventBuffer buffer = new EventBuffer(3);
        for (int i = 1; i <= 5; i++) {
            buffer.offer(record(i));
        }

        assertEquals(List.of(3L, 4L, 5L), sequences(buffer.drain(10)), "최근 셋만 남는다");
    }

    @Test
    @DisplayName("버린 개수를 세어두고, 물어보면 그 뒤로는 다시 0부터 센다")
    void countsDroppedEventsSinceLastAsk() {
        EventBuffer buffer = new EventBuffer(2);
        for (int i = 1; i <= 5; i++) {
            buffer.offer(record(i));
        }

        assertEquals(3, buffer.takeDroppedCount(), "다섯 개를 담고 둘만 남았으니 셋을 버렸다");
        assertEquals(0, buffer.takeDroppedCount(), "같은 구간을 두 번 세지 않는다");
    }

    @Test
    @DisplayName("비어 있으면 기다렸다가 아무것도 없으면 null 을 준다")
    void pollTimesOutWhenEmpty() throws Exception {
        assertNull(new EventBuffer(4).poll(Duration.ofMillis(20)));
    }

    private static EventRecord record(long sequence) {
        return new EventRecord(sequence, 1_700_000_000_000L, new Event.ClientConnected(sequence, "peer"));
    }

    private static List<Long> sequences(List<EventRecord> records) {
        return records.stream().map(EventRecord::sequence).toList();
    }
}
