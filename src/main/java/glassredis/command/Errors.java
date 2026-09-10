package glassredis.command;

import glassredis.resp.RespValue;

import java.util.Locale;

/**
 * 표준 에러 응답 모음.
 *
 * <p>문구를 실제 Redis 와 똑같이 맞춘다. 클라이언트 라이브러리 중에는 에러 메시지를
 * 문자열로 비교하는 것들이 있어서, 다르게 쓰면 호환성이 깨진다.
 * 첫 단어({@code ERR})는 에러 종류를 나타내는 접두어로, Redis 의 관례다.
 */
public final class Errors {

    private Errors() {
    }

    public static RespValue.Err unknownCommand(String name) {
        return new RespValue.Err("ERR unknown command '" + name + "'");
    }

    public static RespValue.Err wrongNumberOfArguments(String commandName) {
        return new RespValue.Err(
                "ERR wrong number of arguments for '" + commandName.toLowerCase(Locale.ROOT) + "' command");
    }

    public static RespValue.Err protocol(String message) {
        return new RespValue.Err("ERR " + message);
    }

    public static RespValue.Err internal(String message) {
        return new RespValue.Err("ERR internal error: " + message);
    }
}
