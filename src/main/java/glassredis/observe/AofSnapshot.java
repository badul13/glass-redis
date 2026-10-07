package glassredis.observe;

import java.util.List;

/**
 * AOF 한 시점 - 실행 스레드에서 생성
 *
 * @param enabled         appendonly
 * @param fsync           appendfsync - always, everysec, no
 * @param files           manifest 순서 - base, history, incr
 * @param currentSize     base + incr 전체 (aof_current_size)
 * @param baseSize        마지막 재작성 직후 크기 (aof_rewrite_base_size) - 증가율 기준
 * @param rewritePercentage auto-aof-rewrite-percentage - 0이면 자동 재작성 꺼짐
 * @param rewriteMinSize  auto-aof-rewrite-min-size
 * @param rewriting       백그라운드 재작성 진행 중
 * @param unsyncedBytes   OS로 넘겼지만 fsync 전인 바이트 - 정전 시 잃을 수 있는 양
 * @param lastFsyncAgoMillis 마지막 fsync 이후 ms - 없으면 -1
 * @param loadStatus      기동 시 복구 결과 - OK, NOT_EXIST, EMPTY, TRUNCATED, FAILED, 꺼져 있으면 OFF
 * @param truncatedFile   복구 중 잘라낸 파일 - 없으면 null
 */
public record AofSnapshot(boolean enabled, String fsync, List<FileView> files, long currentSize, long baseSize,
                          int rewritePercentage, long rewriteMinSize, boolean rewriting, long unsyncedBytes,
                          long lastFsyncAgoMillis, String loadStatus, long loadedCommands, long loadMillis,
                          String truncatedFile, int rewrites) {

    /** @param type 'b' base, 'h' history, 'i' incr */
    public record FileView(String name, char type, long seq, long bytes) {
    }

    public AofSnapshot {
        files = List.copyOf(files);
    }
}
