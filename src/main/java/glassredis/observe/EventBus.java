package glassredis.observe;

/**
 * 내부 사건을 받는 곳. 서버의 모든 모듈은 여기로만 이야기하고 대시보드의 존재를 모른다.
 *
 * <p>관측을 끈 상태에서 오버헤드가 0 이어야 한다. 나중에 처리량을 재는 벤치마크가
 * "대시보드를 켜둔 채 잰 수치"가 되면 그 수치는 아무것도 증명하지 못하기 때문이다.
 *
 * <p>그래서 {@link #NONE} 하나로는 부족하다. 아무 일도 하지 않는 구현을 꽂아도
 * 발행하는 쪽에서 이벤트 객체를 만드는 비용(문자열 자르기, 할당)은 그대로 들기 때문이다.
 * {@link #enabled()} 로 <b>만들기 전에</b> 물어보는 이유가 이것이다.
 *
 * <pre>
 *   if (events.enabled()) {
 *       events.publish(Event.command(...));   // 켜져 있을 때만 이벤트를 만든다
 *   }
 * </pre>
 *
 * <p>구현체는 여러 스레드에서 동시에 불린다. 명령과 만료는 실행 스레드에서,
 * 접속과 종료는 커넥션마다의 가상 스레드에서 발행된다.
 */
public interface EventBus {

    /** 아무것도 받지 않는 구현. 관측을 끄면 이것이 꽂힌다. */
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

    /** 이 호출은 절대 예외를 던지거나 오래 걸리면 안 된다. 발행하는 쪽은 명령을 처리하던 중이다. */
    void publish(Event event);

    /** 이벤트를 만들 가치가 있는지. 꺼져 있으면 발행하는 쪽은 이벤트 객체를 만들지도 말아야 한다. */
    default boolean enabled() {
        return true;
    }
}
