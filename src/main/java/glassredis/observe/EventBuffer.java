package glassredis.observe;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 화면 하나가 가져갈 이벤트를 담아두는 큐. 브라우저 탭 하나가 이것 하나를 쓴다.
 *
 * <p>이 클래스가 있는 이유는 <b>속도 차이</b> 하나다. 서버는 초당 수만 개의 명령을 처리할 수 있지만
 * 브라우저는 그만큼의 줄을 그릴 수 없다. 둘을 직접 연결하면 둘 중 하나가 망가진다 —
 * 발행하는 쪽이 기다리면 대시보드가 Redis 를 느리게 만들고, 무한정 쌓으면 메모리가 터진다.
 *
 * <p>그래서 크기를 고정하고, 넘치면 <b>가장 오래된 것부터 버린다</b>. 실시간 화면에서 밀린 데이터는
 * 이미 가치가 없다. 사용자가 보고 싶은 건 지금 벌어지는 일이지 3초 전에 밀린 줄이 아니다.
 * 버린 개수는 세어두고 화면에 "N개 생략"으로 알린다 — 조용히 버리면 화면에 보이는 것이
 * 전부라고 착각하게 되고, 그건 관측 도구로서 최악이다.
 *
 * <p>화면마다 큐를 따로 두는 것이 중요하다. 하나를 여럿이 나눠 꺼내 가면 탭을 두 개 열었을 때
 * 이벤트가 양쪽으로 갈라져 어느 화면도 전체를 보지 못한다. 느린 화면이 버리는 것도 그 화면 사정일 뿐
 * 다른 화면에 영향을 주지 않는다.
 *
 * <p>담는 쪽은 {@link EventHub}(여러 스레드), 꺼내 가는 쪽은 그 화면을 맡은 스레드 하나다.
 */
public final class EventBuffer {

    /**
     * 기본 용량. 브라우저가 0.1초에 한 번 가져간다고 보면 초당 1만 줄까지는 버리지 않고 버틴다.
     * 그보다 빠른 구간은 어차피 사람이 읽을 수 없는 속도라 버려도 잃는 것이 없다.
     */
    public static final int DEFAULT_CAPACITY = 1024;

    private final BlockingQueue<EventRecord> queue;
    private final AtomicLong dropped = new AtomicLong();

    public EventBuffer() {
        this(DEFAULT_CAPACITY);
    }

    public EventBuffer(int capacity) {
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    /** 담는다. 가득 찼으면 가장 오래된 것을 버리고 담는다. 절대 기다리지 않는다. */
    void offer(EventRecord record) {
        if (queue.offer(record)) {
            return;
        }
        if (queue.poll() != null) {
            dropped.incrementAndGet();
        }
        if (!queue.offer(record)) {
            // 비운 자리를 그 사이 다른 스레드가 차지했다. 여기서 한 번 더 붙잡고 늘어지면
            // 발행하는 쪽(명령을 처리하던 실행 스레드)이 그만큼 느려진다. 이번 사건을 포기한다.
            dropped.incrementAndGet();
        }
    }

    /**
     * 담긴 것을 최대 {@code max} 개까지 꺼내 간다. 꺼낸 것은 버퍼에서 사라진다.
     *
     * <p>비어 있으면 빈 목록을 돌려주고 기다리지 않는다.
     */
    public List<EventRecord> drain(int max) {
        List<EventRecord> taken = new ArrayList<>(Math.min(max, queue.size()));
        queue.drainTo(taken, max);
        return taken;
    }

    /** 하나가 들어올 때까지 기다렸다 꺼낸다. 시간 안에 아무것도 없으면 {@code null}. */
    public EventRecord poll(Duration timeout) throws InterruptedException {
        return queue.poll(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    /**
     * 지난번에 물어본 뒤로 버린 개수를 돌려주고 0 으로 되돌린다.
     *
     * <p>"지금까지 총 몇 개"가 아니라 "이번 구간에 몇 개"를 알아야 화면에서 끊긴 자리에 표시할 수 있다.
     */
    public long takeDroppedCount() {
        return dropped.getAndSet(0);
    }

    /** 지금 담겨 있는 개수. */
    public int size() {
        return queue.size();
    }
}
