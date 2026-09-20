package glassredis.observe;

/**
 * 버퍼에 담긴 이벤트 하나. 사건 자체에 순번과 시각을 붙인 것이다.
 *
 * @param sequence 발행 순서. 1 부터 하나씩 는다. 받아 본 순번이 건너뛰었다면 그 사이는 버려진 것이다.
 *                 SSE 로 내보낼 때 이 값이 그대로 이벤트 id 가 되므로, 브라우저가 끊겼다 붙을 때
 *                 어디까지 받았는지 서버에 알려줄 수 있다.
 * @param atMillis 버퍼에 들어온 시각(에포크 ms). 키스페이스의 시계가 아니라 벽시계다 —
 *                 화면에 "언제 일어난 일인지"를 그리는 용도라 테스트용 가짜 시계를 따라가면 안 된다.
 */
public record EventRecord(long sequence, long atMillis, Event event) {
}
