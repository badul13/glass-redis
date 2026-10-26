package glassredis.server;

import glassredis.command.ServerControl;
import glassredis.observe.RdbSnapshot;
import glassredis.persistence.AppendOnlyLog;
import glassredis.persistence.Snapshot;
import glassredis.resp.RespValue;
import glassredis.store.Keyspace;

/**
 * AOF 재작성·BGSAVE 조율 - 백그라운드 작업은 한 번에 하나 (hasActiveChildProcess)
 * 겹치면 예약해 두고 serverCron 에서 시작
 * 실행 스레드 전용
 */
final class Persistence implements ServerControl {

    private static final RespValue MISCONF = new RespValue.Err("MISCONF Redis is configured to save RDB snapshots, "
            + "but it's currently unable to persist to disk. Commands that may modify the data set are disabled, "
            + "because this instance is configured to report errors during writes if RDB snapshotting fails "
            + "(stop-writes-on-bgsave-error option). Please check the Redis logs for details about the RDB error.");

    private final Keyspace keyspace;
    private final AppendOnlyLog aof;
    /** null이면 RDB 없음 - SAVE·BGSAVE 실패 */
    private final Snapshot snapshot;

    Persistence(Keyspace keyspace, AppendOnlyLog aof, Snapshot snapshot) {
        this.keyspace = keyspace;
        this.aof = aof;
        this.snapshot = snapshot;
    }

    private boolean saving() {
        return snapshot != null && snapshot.saving();
    }

    /** hasActiveChildProcess */
    boolean childActive() {
        return aof.rewriting() || saving();
    }

    /** call() 뒤 - 이번 명령의 변경 횟수 누적 */
    void afterCommand(long dirty) {
        if (snapshot != null) {
            snapshot.addDirty(dirty);
        }
    }

    /** 쓰기 명령·PING 거부 사유 - 받아도 되면 null (writeCommandsDeniedByDiskError) */
    RespValue denyWrites() {
        return snapshot != null && snapshot.deniesWrites() ? MISCONF : null;
    }

    @Override
    public RespValue bgrewriteaof() {
        if (aof.rewriting()) {
            return new RespValue.Err("ERR Background append only file rewriting already in progress");
        }
        if (childActive()) {
            aof.scheduleRewrite();
            return new RespValue.SimpleString("Background append only file rewriting scheduled");
        }
        String failure = aof.rewriteInBackground(keyspace, "BGREWRITEAOF");
        if (failure != null) {
            log("AOF 재작성 시작 실패: " + failure);
            return new RespValue.Err("ERR Can't execute an AOF background rewriting. "
                    + "Please check the server logs for more information.");
        }
        return new RespValue.SimpleString("Background append only file rewriting started");
    }

    @Override
    public RespValue save() {
        if (snapshot == null) {
            return ServerControl.NONE.save();
        }
        if (saving()) {
            return new RespValue.Err("ERR Background save already in progress");
        }
        String failure = snapshot.save(keyspace, "SAVE");
        if (failure != null) {
            log("RDB 저장 실패: " + failure);
            return new RespValue.Err("ERR");
        }
        return RespValue.OK;
    }

    @Override
    public RespValue bgsave(boolean schedule) {
        if (snapshot == null) {
            return ServerControl.NONE.bgsave(schedule);
        }
        if (saving()) {
            return new RespValue.Err("ERR Background save already in progress");
        }
        if (childActive()) {
            if (!schedule) {
                return new RespValue.Err("ERR Another child process is active (AOF?): can't BGSAVE right now. "
                        + "Use BGSAVE SCHEDULE in order to schedule a BGSAVE whenever possible.");
            }
            snapshot.scheduleBgsave();
            return new RespValue.SimpleString("Background saving scheduled");
        }
        snapshot.saveInBackground(keyspace, "BGSAVE");
        return new RespValue.SimpleString("Background saving started");
    }

    @Override
    public long lastSave() {
        return snapshot == null ? 0 : snapshot.lastSave();
    }

    /** 대시보드용 RDB 상태 - RDB 없으면 null */
    RdbSnapshot rdbSnapshot() {
        return snapshot == null ? null : snapshot.view(aof.rewriting());
    }

    /**
     * serverCron 의 저장 부분 - Redis 순서
     * 예약된 재작성 → 끝난 작업 처리 또는 save 규칙·자동 재작성 → 예약된 BGSAVE
     */
    void cron() {
        if (!childActive() && aof.rewriteScheduled()) {
            aof.rewriteInBackground(keyspace, "예약된 BGREWRITEAOF");
        }
        if (childActive()) {
            aof.checkRewriteDone();
            if (snapshot != null) {
                snapshot.checkDone();
            }
        } else {
            Snapshot.SaveParam due = snapshot == null ? null : snapshot.dueSaveParam();
            if (due != null) {
                log("%d초 안에 %d번 이상 바뀌어 백그라운드 저장을 시작합니다", due.seconds(), due.changes());
                snapshot.saveInBackground(keyspace, "save " + due.seconds() + " " + due.changes());
            }
            if (!childActive()) {
                aof.autoRewrite(keyspace);
            }
        }
        if (!childActive() && snapshot != null && snapshot.scheduledBgsaveDue()) {
            snapshot.saveInBackground(keyspace, "예약된 BGSAVE");
        }
    }

    /** prepareForShutdown - AOF 마무리 뒤 save 규칙 있으면 마지막 RDB */
    void shutdown() {
        aof.close();
        if (snapshot == null) {
            return;
        }
        if (!snapshot.config().saveParams().isEmpty()) {
            log("종료 전 마지막 RDB 스냅샷을 저장합니다");
        }
        String failure = snapshot.shutdown(keyspace);
        if (failure != null) {
            log("종료 전 RDB 저장 실패: " + failure);
        }
    }

    /** 저장 없이 정리 - 비정상 종료·테스트 */
    void close() {
        aof.close();
        if (snapshot != null) {
            snapshot.close();
        }
    }

    private static void log(String format, Object... args) {
        System.out.println("[glass-redis] " + String.format(format, args));
    }
}
