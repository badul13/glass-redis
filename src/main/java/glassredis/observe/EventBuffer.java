package glassredis.observe;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 브라우저 탭 하나의 고정 크기 이벤트 큐
 * 넘치면 가장 오래된 것부터 폐기 - 발행 쪽 대기 방지, 폐기 개수는 화면에 통보
 * 담기는 여러 스레드, 꺼내기는 탭 담당 스레드 1개
 */
public final class EventBuffer {

    /** 0.1초 주기 수거 시 초당 1만 건까지 손실 없음 */
    public static final int DEFAULT_CAPACITY = 1024;

    private final BlockingQueue<EventRecord> queue;
    private final AtomicLong dropped = new AtomicLong();

    public EventBuffer() {
        this(DEFAULT_CAPACITY);
    }

    public EventBuffer(int capacity) {
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    /** 가득 차면 가장 오래된 것 폐기 - 대기 없음 */
    void offer(EventRecord record) {
        if (queue.offer(record)) {
            return;
        }
        if (queue.poll() != null) {
            dropped.incrementAndGet();
        }
        if (!queue.offer(record)) {
            // 비운 자리를 다른 스레드가 선점 - 실행 스레드 대기 방지로 이번 건 폐기
            dropped.incrementAndGet();
        }
    }

    /** 비어 있으면 대기 없이 빈 목록 */
    public List<EventRecord> drain(int max) {
        List<EventRecord> taken = new ArrayList<>(Math.min(max, queue.size()));
        queue.drainTo(taken, max);
        return taken;
    }

    /** 시간 내 없으면 null */
    public EventRecord poll(Duration timeout) throws InterruptedException {
        return queue.poll(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    /** 지난 호출 이후 버린 개수 반환 + 0으로 초기화 */
    public long takeDroppedCount() {
        return dropped.getAndSet(0);
    }

    public int size() {
        return queue.size();
    }
}
