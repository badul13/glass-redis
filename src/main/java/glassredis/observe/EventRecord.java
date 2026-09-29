package glassredis.observe;

/**
 * @param sequence 1부터 증가하는 발행 순번 - 건너뛴 번호는 버려진 이벤트
 * @param atMillis 발행 시각(에포크 ms) - 키스페이스 시계 아닌 벽시계
 */
public record EventRecord(long sequence, long atMillis, Event event) {
}
