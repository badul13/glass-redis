package glassredis.command;

import glassredis.resp.RespValue;

import java.util.List;

/**
 * 명령 하나의 구현.
 *
 * <p>인자를 받아 응답 값을 돌려주는 함수 모양으로 잡아둔다.
 * {@link #execute} 는 서버 전체에서 실행 스레드 하나({@code CommandLoop})에서만 호출된다.
 * 그러니 구현체는 동시성을 신경 쓰지 않아도 된다.
 */
public interface Command {

    /** 명령 이름. 대문자로 적는다. */
    String name();

    /**
     * @param ctx  명령이 쓸 수 있는 서버 상태
     * @param args 명령 이름을 <b>제외한</b> 인자들. 값은 바이너리일 수 있으므로 byte[] 그대로 받는다.
     */
    RespValue execute(Context ctx, List<byte[]> args);

    /** 응답을 보낸 뒤 커넥션을 닫아야 하는 명령인지. QUIT 만 true 다. */
    default boolean closesConnection() {
        return false;
    }
}
