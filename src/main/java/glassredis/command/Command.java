package glassredis.command;

import glassredis.resp.RespValue;

import java.util.List;

/** 명령 구현 - execute는 실행 스레드(CommandLoop) 전용이라 동기화 불필요 */
public interface Command {

    /** 대문자 표기 */
    String name();

    /** args - 명령 이름 제외 */
    RespValue execute(Context ctx, List<byte[]> args);

    /** 응답 후 커넥션 종료 여부 - QUIT만 true */
    default boolean closesConnection() {
        return false;
    }
}
