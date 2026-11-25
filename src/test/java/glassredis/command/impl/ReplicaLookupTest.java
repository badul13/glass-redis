package glassredis.command.impl;

import glassredis.resp.RespValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static glassredis.command.impl.CommandTester.bulk;
import static glassredis.command.impl.CommandTester.integer;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 쓰기 가능한 레플리카(replica-read-only no)의 만료 키 - db.c lookupKey
 * 읽기 조회는 없는 것으로만 보고 남김, 쓰기 조회(lookupKeyWrite)는 실제로 지운 뒤 새로 만듦
 */
class ReplicaLookupTest {

    private final CommandTester tester = new CommandTester();

    @BeforeEach
    void expiredKeyOnWritableReplica() {
        tester.run("SET", "k", "5", "PX", "10");
        tester.run("RPUSH", "list", "a");
        tester.run("PEXPIRE", "list", "10");
        tester.keyspace.setReplica(true);
        tester.keyspace.setReplicaReadOnly(false);
        tester.clock.advanceMillis(20);
    }

    @Test
    @DisplayName("읽기 명령 - 만료 키는 nil, 메모리엔 남음 (마스터의 DEL 대기)")
    void readLeavesExpiredKey() {
        assertEquals(RespValue.NIL, tester.run("GET", "k"));
        assertEquals(integer(0), tester.run("LLEN", "list"));
        assertEquals(integer(2), tester.run("DBSIZE"));
    }

    @Test
    @DisplayName("쓰기 명령 - 만료 키를 지우고 새 값으로 시작 (INCR 은 1, RPUSH 는 길이 1)")
    void writeDeletesExpiredKey() {
        assertEquals(integer(1), tester.run("INCR", "k"));
        assertEquals(integer(1), tester.run("RPUSH", "list", "b"));
        assertEquals(bulk("1"), tester.run("GET", "k"));
        assertEquals(integer(-1), tester.run("TTL", "k"), "새 키라 만료 없음");
    }

    @Test
    @DisplayName("DEL - 레플리카의 expireIfNeeded 는 안 지우므로 dbSyncDelete 가 지워 1")
    void delCountsExpiredKeyOnReplica() {
        assertEquals(integer(1), tester.run("DEL", "k"));
    }
}
