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

    /** loading 플래그 - 데이터 로딩 중에도 실행 (그 밖은 -LOADING) */
    default boolean allowedWhileLoading() {
        return false;
    }

    /** 응답 후 커넥션 종료 여부 - QUIT만 true */
    default boolean closesConnection() {
        return false;
    }

    /**
     * commandCheckArity - 인자 수 검사의 유일한 구현, CommandLoop·테스트 도구 공용
     * 명령 안에는 arity 로 못 나타내는 검사만 (PING 최대 개수, MSET·HSET 짝 등)
     *
     * @param argc 이름 포함 인자 수
     * @return 맞으면 null, 틀리면 에러 응답
     */
    static RespValue.Err checkArity(Command command, int argc) {
        int arity = command.arity();
        if ((arity > 0 && argc != arity) || argc < -arity) {
            return Errors.wrongNumberOfArguments(command.name());
        }
        return null;
    }
}
