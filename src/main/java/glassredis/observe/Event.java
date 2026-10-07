package glassredis.observe;

import glassredis.resp.RespValue;

import java.util.ArrayList;
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

    /**
     * beforeSleep에서 OS로 넘긴 AOF 묶음 - 아직 디스크 확정 전일 수 있음
     *
     * @param commands  화면용 명령 문자열 - 최대 AOF_MAX_COMMANDS개
     * @param omitted   생략한 명령 수
     * @param file      쓴 incr 파일
     * @param fileBytes 그 파일 크기
     * @param totalBytes base + incr 전체 크기 (aof_current_size)
     */
    record AofWritten(List<String> commands, int omitted, int bytes, String file, long fileBytes, long totalBytes)
            implements Event {
    }

    /** @param bytes 디스크까지 확정된 파일 위치 */
    record AofFsynced(String file, long bytes, long durationNanos) implements Event {
    }

    /**
     * @param trigger   BGREWRITEAOF 또는 자동(증가율)
     * @param keys      재작성 대상 키 수
     * @param sizeBefore 시작 시 전체 크기
     * @param serializeNanos 실행 스레드에서 명령으로 직렬화한 시간 - 그동안 다른 명령 대기
     * @param newIncr   재작성 중 쓰기를 받을 새 incr 파일
     */
    record AofRewriteStarted(String trigger, int keys, long sizeBefore, long serializeNanos, long baseBytes,
                             String newIncr) implements Event {
    }

    /** @param removedFiles 재작성으로 필요 없어져 지운 파일 */
    record AofRewriteFinished(String base, long baseBytes, long sizeBefore, long sizeAfter, long durationNanos,
                              List<String> removedFiles) implements Event {
    }

    record AofRewriteFailed(String reason) implements Event {
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

    /** AofWritten 명령 표시 최대 개수 */
    int AOF_MAX_COMMANDS = 24;

    /** @param written 이름 포함 명령들 - 앞에서부터 AOF_MAX_COMMANDS개만 문자열로 */
    static AofWritten aofWritten(List<List<byte[]>> written, int bytes, String file, long fileBytes,
                                 long totalBytes) {
        int shown = Math.min(written.size(), AOF_MAX_COMMANDS);
        List<String> commands = new ArrayList<>(shown);
        for (int i = 0; i < shown; i++) {
            commands.add(Display.arguments(written.get(i)));
        }
        return new AofWritten(List.copyOf(commands), written.size() - shown, bytes, file, fileBytes, totalBytes);
    }

    static KeyRemoved keyRemoved(byte[] key, RemovalReason reason, long lateByMillis) {
        return new KeyRemoved(Display.text(key), reason, lateByMillis);
    }
}
