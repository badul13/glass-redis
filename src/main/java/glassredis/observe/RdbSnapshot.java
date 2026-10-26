package glassredis.observe;

import java.util.List;

/**
 * RDB 한 시점 - 실행 스레드에서 생성
 *
 * @param file            dbfilename
 * @param fileBytes       지금 디스크의 파일 크기 - 없으면 -1
 * @param rules           save 규칙 - 비어 있으면 자동 저장 꺼짐
 * @param dirty           마지막 저장 뒤 변경 횟수 (rdb_changes_since_last_save)
 * @param lastSave        마지막 저장 성공 시각, 에포크 초 (rdb_last_save_time)
 * @param sinceSave       그 뒤 지난 초 - save 규칙 판정에 쓰는 값
 * @param saving          BGSAVE 진행 중 (rdb_bgsave_in_progress)
 * @param savingTrigger   진행 중 저장을 시작한 쪽 - 아니면 null
 * @param savingDirty     진행 중 저장이 담은 변경 수 (dirty_before_bgsave)
 * @param scheduled       BGSAVE SCHEDULE 로 미뤄 둔 저장
 * @param lastBgsaveOk    rdb_last_bgsave_status
 * @param retryIn         실패 뒤 자동 저장 재시도까지 남은 초 - 0이면 바로 가능
 * @param deniesWrites    stop-writes-on-bgsave-error 로 쓰기 거부 중 (MISCONF)
 * @param otherChild      AOF 재작성 진행 중 - 그동안 save 규칙 판정 보류
 * @param saves           저장 시도 횟수 (rdb_saves)
 * @param loadStatus      기동 시 복구 - OK, NOT_EXIST, FAILED, AOF 로 복구했으면 AOF
 */
public record RdbSnapshot(String file, long fileBytes, List<Rule> rules, long dirty, long lastSave, long sinceSave,
                          boolean saving, String savingTrigger, long savingDirty, boolean scheduled,
                          boolean lastBgsaveOk, long retryIn, boolean deniesWrites, boolean otherChild, long saves,
                          String loadStatus, long loadedKeys, long loadedExpired, long loadBytes, long loadMillis) {

    /** save 규칙 하나 - seconds 초 초과 + changes 번 이상 */
    public record Rule(long seconds, long changes) {
    }

    public RdbSnapshot {
        rules = List.copyOf(rules);
    }
}
