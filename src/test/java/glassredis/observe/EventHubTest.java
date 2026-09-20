package glassredis.observe;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(10)
class EventHubTest {

    private final EventHub hub = new EventHub();

    @Test
    @DisplayName("보고 있는 화면이 없으면 꺼진 것으로 답한다 — 발행하는 쪽이 이벤트를 만들지도 않게")
    void disabledWhileNobodyWatches() {
        assertFalse(hub.enabled());
        assertEquals(0, hub.screenCount());

        // 아무도 안 볼 때 발행해도 조용히 버려진다.
        hub.publish(new Event.ClientConnected(1, "peer"));

        EventBuffer screen = hub.subscribe();
        assertTrue(hub.enabled());
        assertEquals(List.of(), screen.drain(10), "구독하기 전에 벌어진 일은 받지 않는다");
    }

    @Test
    @DisplayName("화면이 둘이면 같은 사건이 양쪽에 똑같은 순번으로 간다")
    void fansOutToEveryScreen() {
        EventBuffer first = hub.subscribe();
        EventBuffer second = hub.subscribe();

        hub.publish(new Event.ClientConnected(1, "peer"));
        hub.publish(new Event.ClientDisconnected(1));

        List<EventRecord> fromFirst = first.drain(10);
        List<EventRecord> fromSecond = second.drain(10);
        assertEquals(List.of(1L, 2L), fromFirst.stream().map(EventRecord::sequence).toList());
        assertEquals(fromFirst, fromSecond, "탭을 두 개 열어도 각 화면이 전부를 본다");
    }

    @Test
    @DisplayName("구독을 끊은 화면에는 더 보내지 않는다")
    void stopsSendingAfterUnsubscribe() {
        EventBuffer screen = hub.subscribe();
        hub.unsubscribe(screen);

        hub.publish(new Event.ClientConnected(1, "peer"));

        assertFalse(hub.enabled());
        assertEquals(List.of(), screen.drain(10));
    }

    @Test
    @DisplayName("여러 스레드가 동시에 발행해도 순번이 겹치지 않는다")
    void numbersEventsSafelyFromManyThreads() throws Exception {
        int threads = 50;
        int perThread = 100;
        EventBuffer screen = hub.subscribe();

        try (ExecutorService publishers = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> done = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                long id = t;
                done.add(publishers.submit(() -> {
                    for (int i = 0; i < perThread; i++) {
                        hub.publish(new Event.ClientConnected(id, "peer"));
                    }
                }));
            }
            for (Future<?> future : done) {
                future.get();
            }
        }

        // 버퍼 용량(1024)보다 훨씬 많이 넣었으므로 대부분은 버려진다. 남은 것의 순번만 확인한다.
        Set<Long> seen = new HashSet<>();
        for (EventRecord record : screen.drain(threads * perThread)) {
            assertTrue(seen.add(record.sequence()), "같은 순번이 두 번 나왔다: " + record.sequence());
            assertTrue(record.sequence() >= 1 && record.sequence() <= threads * perThread);
        }
        assertEquals(threads * perThread, seen.size() + (int) screen.takeDroppedCount(),
                "받은 것과 버린 것을 더하면 발행한 수가 된다");
    }
}
