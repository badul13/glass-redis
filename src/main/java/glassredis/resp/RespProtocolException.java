package glassredis.resp;

import java.io.IOException;

/**
 * 프레이밍 손상으로 더 읽을 수 없는 경우 - 받는 쪽은 에러 응답 후 커넥션 종료
 * 응답 형태 - -ERR Protocol error: ...
 */
public class RespProtocolException extends IOException {

    public RespProtocolException(String message) {
        super("Protocol error: " + message);
    }
}
