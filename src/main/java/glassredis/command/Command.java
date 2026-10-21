package glassredis.command;

import glassredis.resp.RespValue;

import java.util.List;

/** 명령 구현 - execute는 실행 스레드(CommandLoop) 전용이라 동기화 불필요 */
public interface Command {

    /** 대문자 표기 */
    String name();

    /** args - 명령 이름 제외 */
    RespValue execute(Context ctx, List<byte[]> args);

    /**
     * 이름 포함 인자 수 - 양수는 정확히, 음수는 최소 (commands.def arity)
     * 실행 전 검사 - MISCONF 보다 먼저 (processCommand)
     */
    int arity();

    /** 데이터를 바꿀 수 있는 명령 - write 플래그, 저장 실패 시 거부 대상 (MISCONF) */
    default boolean writes() {
        return false;
    }

    /** 응답 후 커넥션 종료 여부 - QUIT만 true */
    default boolean closesConnection() {
        return false;
    }
}
