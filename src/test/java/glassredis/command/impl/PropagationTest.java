package glassredis.command.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static glassredis.command.impl.CommandTester.integer;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** AOF로 갈 명령 - 기대값은 Docker redis:7(7.4)에 같은 입력을 넣어 받은 incr 파일 */
class PropagationTest {

    private final CommandTester tester = new CommandTester();

    private long now() {
        return tester.clock.millis();
    }

    @Test
    @DisplayName("데이터를 바꾼 명령만 받은 그대로 - 이름 대소문자도 유지")
    void onlyDirtyCommands() {
        tester.run("set", "a", "1");
        tester.run("SET", "a", "2", "NX");
        tester.run("DEL", "nokey");
        tester.run("GET", "a");
        tester.run("DEL", "a", "nokey");
        assertEquals(List.of("set a 1", "DEL a nokey"), tester.propagated);
    }

    @Test
    @DisplayName("SET 상대 만료는 SET key value PXAT 절대ms - 다른 옵션은 버림")
    void setRelativeExpiryBecomesPxat() {
        tester.run("SET", "c", "3", "EX", "100");
        tester.run("SET", "f", "6", "EX", "100", "NX", "GET");
        tester.run("SET", "e", "5", "PXAT", "9999999999999", "GET");
        long at = now() + 100_000;
        assertEquals(List.of("SET c 3 PXAT " + at, "SET f 6 PXAT " + at, "SET e 5 PXAT 9999999999999 GET"),
                tester.propagated);
    }

    @Test
    @DisplayName("만료 없는 SET GET 은 GET 만 뺌")
    void setGetDropsGet() {
        tester.run("SET", "d", "4", "GET");
        assertEquals(List.of("SET d 4"), tester.propagated);
    }

    @Test
    @DisplayName("EXPIRE 계열은 PEXPIREAT 절대ms - 조건 옵션은 받은 그대로, PEXPIREAT 자체는 그대로")
    void expireBecomesPexpireat() {
        tester.run("MSET", "m1", "1", "m2", "2");
        tester.run("EXPIRE", "m1", "100");
        tester.run("expire", "m2", "100", "gt");
        tester.run("PEXPIRE", "m1", "50000", "lt");
        tester.run("EXPIREAT", "m1", "9999999999", "XX");
        tester.run("pexpireat", "m1", "9999999999999");
        assertEquals(List.of(
                "MSET m1 1 m2 2",
                "PEXPIREAT m1 " + (now() + 100_000),
                "PEXPIREAT m1 " + (now() + 50_000) + " lt",
                "PEXPIREAT m1 9999999999000 XX",
                "pexpireat m1 9999999999999"), tester.propagated);
    }

    @Test
    @DisplayName("이미 지난 만료 시각은 DEL - 키도 바로 삭제")
    void pastExpiryBecomesDel() {
        tester.run("SET", "k", "v");
        assertEquals(integer(1), tester.run("EXPIRE", "k", "-1"));
        assertEquals(integer(0), tester.run("EXISTS", "k"));
        assertEquals(List.of("SET k v", "DEL k"), tester.propagated);
    }

    @Test
    @DisplayName("AOF 로딩 중에는 지난 만료 시각도 저장 - 삭제는 로딩 끝난 뒤 만료 처리 몫")
    void loadingKeepsPastExpiry() {
        tester.run("SET", "k", "v");
        tester.keyspace.setLoading(true);
        tester.run("PEXPIREAT", "k", "1");
        tester.keyspace.setLoading(false);
        assertEquals(1, tester.keyspace.size(), "로딩 중 PEXPIREAT 은 키를 지우지 않음");
        assertEquals(integer(0), tester.run("EXISTS", "k"), "로딩 후 첫 접근에서 만료");
        assertEquals(List.of("SET k v", "PEXPIREAT k 1", "DEL k"), tester.propagated);
    }

    @Test
    @DisplayName("변화 없는 자료형 명령은 전파 안 함 - LTRIM 전체, ZADD 같은 점수, ZINCRBY 0, 없는 멤버 삭제")
    void noOpCollectionCommands() {
        tester.run("RPUSH", "l", "a", "b");
        tester.run("LTRIM", "l", "0", "-1");
        tester.run("LPOP", "l", "0");
        tester.run("LREM", "l", "0", "nothere");
        tester.run("ZADD", "z", "1", "a");
        tester.run("ZADD", "z", "1", "a");
        tester.run("ZADD", "z", "XX", "5", "nope");
        tester.run("ZINCRBY", "z", "0", "a");
        tester.run("ZREM", "z", "nope");
        tester.run("SADD", "s", "1");
        tester.run("SADD", "s", "1");
        tester.run("SREM", "s", "9");
        tester.run("HSET", "h", "f", "1");
        tester.run("HDEL", "h", "nope");
        tester.run("PERSIST", "h");
        assertEquals(List.of("RPUSH l a b", "ZADD z 1 a", "SADD s 1", "HSET h f 1"), tester.propagated);
    }

    @Test
    @DisplayName("값이 같아도 HSET 은 전파 - 덮어쓰기 자체가 변경")
    void hsetSameValueStillPropagated() {
        tester.run("HSET", "h", "f", "1");
        tester.run("HSET", "h", "f", "1");
        assertEquals(List.of("HSET h f 1", "HSET h f 1"), tester.propagated);
    }

    @Test
    @DisplayName("읽다가 만료된 키는 DEL 단독")
    void lazyExpireOnRead() {
        tester.run("SET", "x", "1", "PX", "1");
        tester.clock.advanceMillis(2);
        tester.run("GET", "x");
        assertEquals(List.of("SET x 1 PXAT " + (now() - 1), "DEL x"), tester.propagated);
    }

    @Test
    @DisplayName("쓰기 명령이 만료 키를 건드리면 DEL 과 그 명령을 MULTI/EXEC 로 묶음")
    void lazyExpireWithWriteIsTransaction() {
        tester.run("SET", "y", "1", "PX", "1");
        tester.clock.advanceMillis(2);
        tester.run("SET", "y", "2");
        tester.run("DEL", "y");
        assertEquals(List.of("SET y 1 PXAT " + (now() - 1), "MULTI", "DEL y", "SET y 2", "EXEC", "DEL y"),
                tester.propagated);
    }

    @Test
    @DisplayName("만료 키 DEL 은 지운 게 없어도 만료 DEL 하나만 - DEL 명령 자체는 dirty 0")
    void delOfExpiredKey() {
        tester.run("SET", "p", "1", "PX", "1");
        tester.clock.advanceMillis(2);
        assertEquals(integer(0), tester.run("DEL", "p"));
        assertEquals(List.of("SET p 1 PXAT " + (now() - 1), "DEL p"), tester.propagated);
    }

    @Test
    @DisplayName("POP 은 꺼낸 만큼, 비면 키 삭제까지 명령 하나")
    void popPropagatesAsIs() {
        tester.run("RPUSH", "l", "a", "b", "c");
        tester.run("RPOP", "l", "2");
        tester.run("LPOP", "l");
        assertEquals(List.of("RPUSH l a b c", "RPOP l 2", "LPOP l"), tester.propagated);
        assertEquals(0, tester.keyspace.size());
    }
}
