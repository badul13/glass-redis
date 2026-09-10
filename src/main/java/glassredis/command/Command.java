package glassredis.command;

import glassredis.resp.RespValue;

import java.util.List;

/**
 * 명령 하나의 구현.
 *
 * <p>인자를 받아 응답 값을 돌려주는 순수한 함수 모양으로 잡아둔다.
 * 지금(0단계)은 커넥션을 처리하는 가상 스레드가 이걸 직접 호출하지만,
 * 1단계에서 단일 실행 스레드를 도입하면 호출 지점만 큐 투입으로 바뀌고
 * 이 인터페이스는 그대로 쓸 수 있다.
 */
public interface Command {

    /** 명령 이름. 대문자로 적는다. */
    String name();

    /**
     * @param args 명령 이름을 <b>제외한</b> 인자들. 값은 바이너리일 수 있으므로 byte[] 그대로 받는다.
     */
    RespValue execute(List<byte[]> args);

    /** 응답을 보낸 뒤 커넥션을 닫아야 하는 명령인지. QUIT 만 true 다. */
    default boolean closesConnection() {
        return false;
    }
}
