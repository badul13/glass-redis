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
    @DisplayName("보는 화면이 없으면 비활성으로 응답 - 발행 측의 이벤트 생성 자체를 생략")
    void disabledWhileNobodyWatches() {
        assertFalse(hub.enabled());
        assertEquals(0, hub.screenCount());

        // 보는 화면이 없으면 발행해도 폐기
        hub.publish(new Event.ClientConnected(1, "peer"));

        EventBuffer screen = hub.subscribe();
        assertTrue(hub.enabled());
        assertEquals(List.of(), screen.drain(10), "구독하기 전에 벌어진 일은 받지 않는다");
    }

    @Test
    @DisplayName("화면이 둘이면 같은 사건을 양쪽에 같은 순번으로 전달")
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
    @DisplayName("구독 해제한 화면에는 전송 중단")
    void stopsSendingAfterUnsubscribe() {
        EventBuffer screen = hub.subscribe();
        hub.unsubscribe(screen);

        hub.publish(new Event.ClientConnected(1, "peer"));

        assertFalse(hub.enabled());
        assertEquals(List.of(), screen.drain(10));
    }

    @Test
    @DisplayName("여러 스레드 동시 발행에도 순번 중복 없음")
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

        // 버퍼 용량(1024) 초과로 대부분 폐기 - 남은 것의 순번만 확인
        Set<Long> seen = new HashSet<>();
        for (EventRecord record : screen.drain(threads * perThread)) {
            assertTrue(seen.add(record.sequence()), "같은 순번이 두 번 나왔다: " + record.sequence());
            assertTrue(record.sequence() >= 1 && record.sequence() <= threads * perThread);
        }
        assertEquals(threads * perThread, seen.size() + (int) screen.takeDroppedCount(),
                "받은 것과 버린 것을 더하면 발행한 수가 된다");
    }
}
