package glassredis.resp;

import java.io.IOException;

/**
 * 스트림의 프레이밍이 깨졌을 때 던진다.
 *
 * <p>"모르는 명령"이나 "인자 개수 오류"와는 성격이 다르다. 그런 것들은 프레이밍이 멀쩡하므로
 * 에러만 응답하고 다음 명령을 이어서 읽으면 된다. 반면 이 예외는 어디까지가 한 명령인지
 * 알 수 없게 된 상태라, 더 읽어봐야 쓰레기만 나온다. 받는 쪽은 에러를 쓰고 커넥션을 닫아야 한다.
 *
 * <p>메시지는 {@code "Protocol error: ..."} 로 시작하도록 맞춘다.
 * 응답으로 나갈 때 앞에 {@code ERR } 접두어가 붙어 {@code -ERR Protocol error: ...} 가 된다.
 */
public class RespProtocolException extends IOException {

    public RespProtocolException(String message) {
        super("Protocol error: " + message);
    }
}
