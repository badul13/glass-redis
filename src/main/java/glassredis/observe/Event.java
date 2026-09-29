package glassredis.observe;

import glassredis.resp.RespValue;

import java.util.List;

/**
 * 서버 사건 하나 - 값은 Display로 이미 자른 문자열
 * 시각은 EventBus 진입 시 EventRecord에서 부여
 */
public sealed interface Event {

    record ClientConnected(long connectionId, String peer) implements Event {
    }

    record ClientDisconnected(long connectionId) implements Event {
    }

    /** @param durationNanos 실행 스레드 소요 시간 - 소켓 입출력 제외 */
    record CommandExecuted(long connectionId, String name, String arguments, long durationNanos, String reply)
            implements Event {
    }

    /** @param lateByMillis 만료 시각부터 실제 삭제까지 지연 - DELETED면 0 */
    record KeyRemoved(String key, RemovalReason reason, long lateByMillis) implements Event {
    }

    /**
     * 주기적 만료 1회 - 볼 키가 없어 헛돈 주기는 발행 제외
     *
     * @param kind          SLOW(주기 작업, 최대 25ms) 또는 FAST(쉬기 직전, 최대 1ms)
     * @param rounds        10% 규칙 반복 바퀴 수
     * @param timeLimitHit  시간 한도로 일을 남긴 채 종료 여부
     * @param stalePercent  만료됐지만 남아 있는 키의 비율 추정치(%)
     */
    record ExpiryCycleCompleted(String kind, int rounds, int sampled, int expired, long durationNanos,
                                boolean timeLimitHit, double stalePercent) implements Event {
    }

    enum RemovalReason {
        /** 읽기 시 만료 확인 후 삭제 */
        LAZY_EXPIRED,
        /** 주기적 만료(SLOW·FAST)로 삭제 */
        ACTIVE_EXPIRED,
        /** DEL로 삭제 */
        DELETED
    }

    static CommandExecuted command(long connectionId, String name, List<byte[]> args,
                                   long durationNanos, RespValue reply) {
        return new CommandExecuted(connectionId, name, Display.arguments(args), durationNanos, Display.reply(reply));
    }

    static KeyRemoved keyRemoved(byte[] key, RemovalReason reason, long lateByMillis) {
        return new KeyRemoved(Display.text(key), reason, lateByMillis);
    }
}
