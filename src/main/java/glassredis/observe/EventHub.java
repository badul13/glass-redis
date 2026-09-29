package glassredis.observe;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 발행 이벤트를 구독 화면마다 분배 - 구독 없으면 enabled() false
 * 순번은 여기서 한 번 부여 - 모든 화면이 같은 번호
 * COW 리스트 - 구독 변경은 드물고 발행은 빈번
 */
public final class EventHub implements EventBus {

    private final CopyOnWriteArrayList<EventBuffer> screens = new CopyOnWriteArrayList<>();
    private final AtomicLong nextSequence = new AtomicLong(1);

    /** 종료 시 unsubscribe 호출 필수 */
    public EventBuffer subscribe() {
        EventBuffer screen = new EventBuffer();
        screens.add(screen);
        return screen;
    }

    public void unsubscribe(EventBuffer screen) {
        screens.remove(screen);
    }

    public int screenCount() {
        return screens.size();
    }

    @Override
    public void publish(Event event) {
        if (screens.isEmpty()) {
            return;
        }
        EventRecord record = new EventRecord(nextSequence.getAndIncrement(), System.currentTimeMillis(), event);
        for (EventBuffer screen : screens) {
            screen.offer(record);
        }
    }

    @Override
    public boolean enabled() {
        return !screens.isEmpty();
    }
}
