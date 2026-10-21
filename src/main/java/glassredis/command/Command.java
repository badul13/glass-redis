package glassredis.command;

import glassredis.resp.RespValue;

import java.util.List;

/** 명령 구현 - execute는 실행 스레드(CommandLoop) 전용이라 동기화 불필요 */
public interface Command {

    /** 대문자 표기 */
    String name();

    /** args - 명령 이름 제외 */
    RespValue execute(Context ctx, List<byte[]> args);

    /** 데이터를 바꿀 수 있는 명령 - write 플래그, 저장 실패 시 거부 대상 (MISCONF) */
    default boolean writes() {
        return false;
    }

    /** 응답 후 커넥션 종료 여부 - QUIT만 true */
    default boolean closesConnection() {
        return false;
    }
}
