package glassredis.server;

import glassredis.command.CommandRegistry;
import glassredis.observe.EventBus;
import glassredis.persistence.AppendOnlyLog;
import glassredis.persistence.Snapshot;
import glassredis.resp.RespValue;
import glassredis.store.Entry;
import glassredis.store.Key;
import glassredis.store.Keyspace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 백그라운드 작업은 한 번에 하나 - 겹치면 에러 또는 예약, cron 이 이어서 시작 */
@Timeout(20)
class PersistenceTest {

    @TempDir
    Path dir;

    private Persistence persistence;

    @AfterEach
    void close() {
        if (persistence != null) {
            persistence.close();
        }
    }

    private Persistence open() throws Exception {
        Keyspace keyspace = new Keyspace();
        keyspace.put(Key.of("k"), Entry.of("v".getBytes(StandardCharsets.UTF_8)));
        AppendOnlyLog aof = new AppendOnlyLog(new AppendOnlyLog.Config(dir, true, AppendOnlyLog.FsyncPolicy.EVERYSEC,
                100, 64L << 20, false), EventBus.NONE);
        aof.start(keyspace, CommandRegistry.withBuiltins());
        persistence = new Persistence(keyspace, aof, new Snapshot(Snapshot.Config.defaults(dir)));
        return persistence;
    }

    private static String status(RespValue reply) {
        return switch (reply) {
            case RespValue.SimpleString s -> "+" + s.text();
            case RespValue.Err e -> "-" + e.message();
            default -> reply.toString();
        };
    }

    /** checkChildrenDone 은 cron 에서만 - 작업이 끝나고 조건이 맞을 때까지 */
    private static void cronUntil(Persistence persistence, BooleanSupplier done) throws InterruptedException {
        do {
            Thread.sleep(5);
            persistence.cron();
        } while (persistence.childActive() || !done.getAsBoolean());
    }

    @Test
    @DisplayName("재작성 중 BGSAVE - 그냥은 에러, SCHEDULE 이면 재작성 끝난 뒤 시작")
    void bgsaveDuringRewrite() throws Exception {
        Persistence persistence = open();
        assertEquals("+Background append only file rewriting started", status(persistence.bgrewriteaof()));
        assertEquals("-ERR Background append only file rewriting already in progress",
                status(persistence.bgrewriteaof()));
        assertEquals("-ERR Another child process is active (AOF?): can't BGSAVE right now. "
                + "Use BGSAVE SCHEDULE in order to schedule a BGSAVE whenever possible.", status(persistence.bgsave(false)));
        assertEquals("+Background saving scheduled", status(persistence.bgsave(true)));
        assertFalse(Files.exists(dir.resolve("dump.rdb")));

        cronUntil(persistence, () -> Files.exists(dir.resolve("dump.rdb")));
        assertTrue(Files.exists(dir.resolve("dump.rdb")), "재작성 끝난 cron 에서 예약된 BGSAVE 시작");
    }

    @Test
    @DisplayName("BGSAVE 중 BGREWRITEAOF - 예약, SAVE·BGSAVE 는 에러")
    void rewriteDuringBgsave() throws Exception {
        Persistence persistence = open();
        assertEquals("+Background saving started", status(persistence.bgsave(false)));
        assertEquals("-ERR Background save already in progress", status(persistence.bgsave(true)));
        assertEquals("-ERR Background save already in progress", status(persistence.save()));
        assertEquals("+Background append only file rewriting scheduled", status(persistence.bgrewriteaof()));

        Path base = dir.resolve(AppendOnlyLog.DIRNAME).resolve("appendonly.aof.2.base.aof");
        cronUntil(persistence, () -> Files.exists(base));
        assertTrue(Files.exists(base), "BGSAVE 끝난 다음 cron 에서 예약된 재작성 시작·완료");
    }
}
