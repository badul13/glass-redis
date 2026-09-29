package glassredis.observe;

/**
 * 서버 사건 발행 창구
 * 발행 쪽은 enabled() 먼저 확인 후 이벤트 생성 - 꺼져 있을 때 비용 0
 * 실행 스레드·커넥션 스레드에서 동시 호출
 */
public interface EventBus {

    EventBus NONE = new EventBus() {
        @Override
        public void publish(Event event) {
        }

        @Override
        public boolean enabled() {
            return false;
        }

        @Override
        public String toString() {
            return "EventBus.NONE";
        }
    };

    /** 명령 처리 중 호출 - 예외·지연 금지 */
    void publish(Event event);

    /** false면 이벤트 객체 생성 생략 */
    default boolean enabled() {
        return true;
    }
}
