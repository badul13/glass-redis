package glassredis.observe;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 발행된 이벤트를 보고 있는 화면들에 나눠준다. 서버 쪽에서 보면 이것이 {@link EventBus} 의 실체다.
 *
 * <p>보고 있는 화면이 하나도 없으면 {@link #enabled()} 가 {@code false} 다. 그러면 발행하는 쪽은
 * 이벤트 객체를 만들지도 않는다. 대시보드를 켜둔 채 브라우저만 닫아도 서버는 원래 속도로 돌아간다.
 *
 * <p>순번과 시각은 여기서 붙인다. 화면마다 붙이면 같은 사건이 화면마다 다른 번호를 갖게 되고,
 * 번호가 건너뛴 자리를 보고 "버려졌다"고 판단할 수 없게 된다.
 *
 * <p>구독 목록은 {@link CopyOnWriteArrayList} 다. 구독이 생기고 사라지는 건 사람이 탭을 여닫을 때뿐이라
 * 아주 드물고, 대신 발행은 명령마다 일어난다. 읽기에 락이 없는 쪽이 맞다.
 */
public final class EventHub implements EventBus {

    private final CopyOnWriteArrayList<EventBuffer> screens = new CopyOnWriteArrayList<>();
    private final AtomicLong nextSequence = new AtomicLong(1);

    /** 새 화면 하나가 볼 버퍼를 만들어 등록한다. 다 보고 나면 {@link #unsubscribe} 로 반드시 돌려줘야 한다. */
    public EventBuffer subscribe() {
        EventBuffer screen = new EventBuffer();
        screens.add(screen);
        return screen;
    }

    public void unsubscribe(EventBuffer screen) {
        screens.remove(screen);
    }

    /** 지금 보고 있는 화면 수. */
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
