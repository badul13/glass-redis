package glassredis.command.impl;

import glassredis.resp.RespValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static glassredis.command.impl.CommandTester.bulk;
import static glassredis.command.impl.CommandTester.error;
import static glassredis.command.impl.CommandTester.integer;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** 만료가 얽힌 명령들. 시계를 손으로 돌려서 확인한다. */
class ExpireCommandsTest {

    private final CommandTester tester = new CommandTester();

    // --- SET 의 만료 옵션 ---

    @Test
    @DisplayName("SET EX 로 건 만료를 TTL 은 반올림한 초로, PTTL 은 밀리초로 준다")
    void setExThenTtl() {
        run("SET", "k", "v", "EX", "10");
        assertEquals(integer(10), run("TTL", "k"));

        advance(9_400); // 600ms 남음
        assertEquals(integer(1), run("TTL", "k"), "0.6초는 버리면 0, 반올림하면 1");
        assertEquals(integer(600), run("PTTL", "k"));
    }

    @Test
    @DisplayName("만료 시각이 지나면 GET 은 nil 이고 TTL 은 -2 다")
    void expiredKeyLooksMissing() {
        run("SET", "k", "v", "PX", "100");

        advance(100);
        assertEquals(bulk("v"), run("GET", "k"), "만료 시각과 같은 ms 에는 아직 살아 있다");

        advance(1);
        assertEquals(RespValue.NIL, run("GET", "k"));
        assertEquals(integer(-2), run("TTL", "k"));
    }

    @Test
    @DisplayName("옵션 없는 SET 은 기존 만료를 지운다")
    void plainSetClearsTtl() {
        run("SET", "k", "v", "EX", "10");
        run("SET", "k", "v2");

        assertEquals(integer(-1), run("TTL", "k"));
    }

    @Test
    @DisplayName("SET KEEPTTL 은 값만 바꾸고 기존 만료 시각을 유지한다")
    void keepTtl() {
        run("SET", "k", "v", "EX", "10");
        advance(3_000);
        run("SET", "k", "v2", "KEEPTTL");

        assertEquals(integer(7), run("TTL", "k"));
        assertEquals(bulk("v2"), run("GET", "k"));
    }

    @Test
    @DisplayName("SET 의 만료 값이 틀리면 종류에 따라 다른 에러를 준다")
    void setExpiryErrors() {
        assertEquals(error("ERR value is not an integer or out of range"), run("SET", "k", "v", "EX", "ten"));
        assertEquals(error("ERR invalid expire time in 'set' command"), run("SET", "k", "v", "EX", "0"));
        assertEquals(error("ERR invalid expire time in 'set' command"), run("SET", "k", "v", "PX", "-5"));
        assertEquals(error("ERR invalid expire time in 'set' command"),
                run("SET", "k", "v", "EX", "9223372036854775807"));
        assertEquals(error("ERR invalid expire time in 'set' command"), run("SET", "k", "v", "EXAT", "0"));
        assertEquals(error("ERR syntax error"), run("SET", "k", "v", "EX"));
        assertEquals(error("ERR syntax error"), run("SET", "k", "v", "EX", "10", "PX", "100"));
        assertEquals(error("ERR syntax error"), run("SET", "k", "v", "EX", "10", "EXAT", "100"));
        assertEquals(error("ERR syntax error"), run("SET", "k", "v", "EX", "10", "KEEPTTL"));
        assertEquals(error("ERR syntax error"), run("SET", "k", "v", "KEEPTTL", "PX", "10"));
        assertEquals(RespValue.NIL, run("GET", "k"));
    }

    @Test
    @DisplayName("옵션 조합을 먼저 다 본 뒤에 만료 값을 해석한다 — 문법 오류가 숫자 오류보다 먼저다")
    void setChecksSyntaxBeforeNumbers() {
        assertEquals(error("ERR syntax error"), run("SET", "k", "v", "EX", "ten", "BOGUS"));
        assertEquals(RespValue.NIL, run("GET", "k"));
    }

    @Test
    @DisplayName("같은 만료 옵션을 두 번 주면 마지막 값만 해석해서 쓴다")
    void setRepeatedExpiryUsesLast() {
        assertEquals(RespValue.OK, run("SET", "k", "v", "EX", "10", "EX", "20"));
        assertEquals(integer(20), run("TTL", "k"));

        assertEquals(RespValue.OK, run("SET", "k", "v", "EX", "ten", "EX", "30"));
        assertEquals(integer(30), run("TTL", "k"));
    }

    @Test
    @DisplayName("EXAT, PXAT 는 유닉스 시각으로 만료를 걸고, 이미 지난 시각이면 곧바로 없는 키로 보인다")
    void setAbsoluteExpiry() {
        long nowSeconds = tester.clock.millis() / 1000;

        run("SET", "k", "v", "EXAT", String.valueOf(nowSeconds + 100));
        assertEquals(integer(100), run("TTL", "k"));

        run("SET", "p", "v", "PXAT", String.valueOf(tester.clock.millis() + 500));
        assertEquals(integer(500), run("PTTL", "p"));

        assertEquals(RespValue.OK, run("SET", "old", "v", "EXAT", "1"));
        assertEquals(RespValue.NIL, run("GET", "old"));
    }

    // --- TTL / EXPIRE / PERSIST ---

    @Test
    @DisplayName("TTL 은 키가 없으면 -2, 만료 시각이 없으면 -1")
    void ttlSpecialValues() {
        run("SET", "persistent", "v");

        assertEquals(integer(-2), run("TTL", "missing"));
        assertEquals(integer(-1), run("TTL", "persistent"));
        assertEquals(integer(-1), run("PTTL", "persistent"));
    }

    @Test
    @DisplayName("EXPIRE 는 있는 키에만 걸고, PEXPIRE 는 밀리초 단위다")
    void expireAndPexpire() {
        assertEquals(integer(0), run("EXPIRE", "missing", "10"));

        run("SET", "k", "v");
        assertEquals(integer(1), run("EXPIRE", "k", "10"));
        assertEquals(integer(10), run("TTL", "k"));

        assertEquals(integer(1), run("PEXPIRE", "k", "1500"));
        assertEquals(integer(1500), run("PTTL", "k"));
    }

    @Test
    @DisplayName("EXPIRE 에 0 이하를 주면 샘플링을 기다리지 않고 그 자리에서 지운다")
    void expireInThePastDeletesImmediately() {
        run("SET", "k", "v");

        assertEquals(integer(1), run("EXPIRE", "k", "0"));
        assertEquals(0, tester.keyspace.size(), "메모리에서도 바로 사라져야 한다");
        assertEquals(integer(0), run("EXISTS", "k"));
    }

    @Test
    @DisplayName("EXPIRE 의 값이나 옵션이 틀리면 에러를 주고, 옵션 검사가 숫자·키 검사보다 먼저다")
    void expireErrors() {
        run("SET", "k", "v");

        assertEquals(error("ERR value is not an integer or out of range"), run("EXPIRE", "k", "soon"));
        assertEquals(error("ERR Unsupported option FOO"), run("EXPIRE", "k", "10", "FOO"));
        assertEquals(error("ERR Unsupported option FOO"), run("EXPIRE", "missing", "ten", "FOO"));
        assertEquals(error("ERR NX and XX, GT or LT options at the same time are not compatible"),
                run("EXPIRE", "k", "10", "NX", "XX"));
        assertEquals(error("ERR NX and XX, GT or LT options at the same time are not compatible"),
                run("EXPIRE", "missing", "ten", "NX", "GT"));
        assertEquals(error("ERR GT and LT options at the same time are not compatible"),
                run("EXPIRE", "k", "10", "GT", "LT"));
        assertEquals(error("ERR invalid expire time in 'expire' command"),
                run("EXPIRE", "k", "-9223372036854775808"));
        assertEquals(integer(-1), run("TTL", "k"));
    }

    @Test
    @DisplayName("EXPIRE NX 는 만료가 없을 때만, XX 는 있을 때만 건다")
    void expireNxXx() {
        run("SET", "k", "v");

        assertEquals(integer(0), run("EXPIRE", "k", "10", "XX"));
        assertEquals(integer(-1), run("TTL", "k"));
        assertEquals(integer(1), run("EXPIRE", "k", "10", "NX"));
        assertEquals(integer(0), run("EXPIRE", "k", "20", "nx"));
        assertEquals(integer(10), run("TTL", "k"));
        assertEquals(integer(1), run("EXPIRE", "k", "30", "XX"));
        assertEquals(integer(30), run("TTL", "k"));
    }

    @Test
    @DisplayName("EXPIRE GT/LT 는 새 시각이 늦을 때/이를 때만 걸고, 만료 없음은 무한대로 친다")
    void expireGtLt() {
        run("SET", "k", "v");

        assertEquals(integer(0), run("EXPIRE", "k", "10", "GT"), "무한대보다 늦을 수는 없다");
        assertEquals(integer(1), run("EXPIRE", "k", "50", "LT"), "무한대보다는 무엇이든 이르다");
        assertEquals(integer(0), run("EXPIRE", "k", "60", "LT"));
        assertEquals(integer(1), run("EXPIRE", "k", "40", "lt"));
        assertEquals(integer(0), run("EXPIRE", "k", "30", "GT"));
        assertEquals(integer(1), run("EXPIRE", "k", "100", "GT"));
        assertEquals(integer(0), run("EXPIRE", "k", "10", "XX", "GT"));
        assertEquals(integer(100), run("TTL", "k"));

        assertEquals(integer(1), run("PEXPIRE", "k", "200000", "gt"));
        assertEquals(integer(200000), run("PTTL", "k"));
    }

    @Test
    @DisplayName("LT 로 과거 시각을 걸면 조건을 통과해 그 자리에서 지워지고, GT 는 만료 없는 키에서 실패한다")
    void expireConditionWithPastTime() {
        run("SET", "k", "v");

        assertEquals(integer(0), run("EXPIRE", "k", "-1", "GT"));
        assertEquals(integer(1), run("EXISTS", "k"));
        assertEquals(integer(1), run("EXPIRE", "k", "-1", "LT"));
        assertEquals(integer(0), run("EXISTS", "k"));
    }

    @Test
    @DisplayName("PERSIST 는 만료 시각을 지웠을 때만 1 이다")
    void persist() {
        run("SET", "k", "v", "EX", "10");

        assertEquals(integer(1), run("PERSIST", "k"));
        assertEquals(integer(-1), run("TTL", "k"));
        assertEquals(integer(0), run("PERSIST", "k"));
        assertEquals(integer(0), run("PERSIST", "missing"));
    }

    // --- 다른 명령과 만료의 관계 ---

    @Test
    @DisplayName("INCR, APPEND 처럼 값을 고치는 명령은 만료 시각을 유지한다")
    void modifyingCommandsKeepTtl() {
        run("SET", "n", "1", "EX", "10");
        run("INCR", "n");
        run("APPEND", "n", "0");

        assertEquals(bulk("20"), run("GET", "n"));
        assertEquals(integer(10), run("TTL", "n"));
    }

    @Test
    @DisplayName("MSET 은 옵션 없는 SET 처럼 기존 만료를 지운다")
    void msetClearsTtl() {
        run("SET", "k", "v", "EX", "10");
        run("MSET", "k", "v2");

        assertEquals(integer(-1), run("TTL", "k"));
    }

    @Test
    @DisplayName("만료된 키에 INCR 하면 0 에서 새로 시작하고 만료 시각도 없다")
    void incrOnExpiredKeyStartsFresh() {
        run("SET", "n", "41", "PX", "10");
        advance(11);

        assertEquals(integer(1), run("INCR", "n"));
        assertEquals(integer(-1), run("TTL", "n"));
    }

    private RespValue run(String... argv) {
        return tester.run(argv);
    }

    private void advance(long millis) {
        tester.clock.advanceMillis(millis);
    }
}
