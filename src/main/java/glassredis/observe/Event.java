package glassredis.observe;

import glassredis.resp.RespValue;

import java.util.List;

/**
 * 서버 안에서 벌어진 일 하나.
 *
 * <p>대시보드가 그리는 모든 화면의 재료다. 서버 코드는 이 값을 {@link EventBus} 로 던지기만 하고
 * 누가 받아 보는지는 모른다.
 *
 * <p>여기 담기는 건 <b>화면에 그릴 수 있는 형태로 이미 가공된 값</b>이다. 키와 인자는 바이너리일 수 있고
 * 수 MB 짜리일 수도 있는데, 그걸 그대로 들고 있으면 두 가지가 곤란해진다.
 * 버퍼가 잡아먹는 메모리가 값 크기에 비례해 늘어나고, 화면에 그리려면 어차피 누군가는 잘라야 한다.
 * 그래서 발행하는 순간에 잘라서 문자열로 만든다. 이 비용은 관측이 켜져 있을 때만 든다.
 *
 * <p>시각은 담지 않는다. 사건이 일어난 시각은 {@link EventBus} 에 들어가는 순간
 * {@link EventRecord} 가 붙여준다. 그래야 각 발행 지점이 시계를 들고 다니지 않아도 되고,
 * 테스트에서 시계를 손으로 돌려도 이벤트의 시각은 실제 시각으로 남는다.
 */
public sealed interface Event {

    /** 클라이언트가 접속했다. */
    record ClientConnected(long connectionId, String peer) implements Event {
    }

    /** 클라이언트가 끊었다. */
    record ClientDisconnected(long connectionId) implements Event {
    }

    /**
     * 명령 하나가 실행됐다.
     *
     * @param durationNanos 실행 스레드가 이 명령을 붙잡고 있던 시간. 소켓 입출력은 포함하지 않는다 —
     *                      그건 커넥션 스레드가 하는 일이라 실행 스레드의 부담이 아니다.
     */
    record CommandExecuted(long connectionId, String name, String arguments, long durationNanos, String reply)
            implements Event {
    }

    /**
     * 키 하나가 키스페이스에서 사라졌다.
     *
     * @param lateByMillis 만료 시각이 지나고 실제로 지워지기까지 걸린 시간. 이 값이 0 이 아니라는 것이
     *                     "아무도 정확히 10초에 지우지 않는다"는 말의 증거다.
     *                     {@link RemovalReason#DELETED} 면 만료와 무관하므로 0 이다.
     */
    record KeyRemoved(String key, RemovalReason reason, long lateByMillis) implements Event {
    }

    /**
     * 주기적 만료 샘플링이 한 번 돌았다.
     *
     * <p>뽑을 키가 하나도 없어서 아무 일도 하지 않은 주기는 발행하지 않는다. 100ms 마다 "할 일 없음"을
     * 보내봐야 화면에 그릴 것이 없고 버퍼만 밀어낸다.
     *
     * @param rounds 25% 규칙에 걸려 몇 바퀴를 돌았는지
     */
    record ExpiryCycleCompleted(int rounds, int sampled, int expired, long durationNanos) implements Event {
    }

    /** 키가 사라진 이유. 같은 삭제라도 누가 지웠는지가 이 프로젝트에서 제일 보고 싶은 것이다. */
    enum RemovalReason {
        /** 읽을 때 만료가 확인돼서 그 자리에서 지웠다. */
        LAZY_EXPIRED,
        /** 주기적 샘플링에 걸려서 지웠다. 아무도 읽지 않는 키는 이 경로로만 사라진다. */
        ACTIVE_EXPIRED,
        /** {@code DEL} 명령으로 지웠다. */
        DELETED
    }

    /** 실행된 명령 하나를 화면에 그릴 수 있는 형태로 만든다. */
    static CommandExecuted command(long connectionId, String name, List<byte[]> args,
                                   long durationNanos, RespValue reply) {
        return new CommandExecuted(connectionId, name, Display.arguments(args), durationNanos, Display.reply(reply));
    }

    /** 사라진 키 하나를 화면에 그릴 수 있는 형태로 만든다. */
    static KeyRemoved keyRemoved(byte[] key, RemovalReason reason, long lateByMillis) {
        return new KeyRemoved(Display.text(key), reason, lateByMillis);
    }
}
