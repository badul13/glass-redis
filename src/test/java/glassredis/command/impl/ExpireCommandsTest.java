package glassredis.command.impl;

import glassredis.resp.RespValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static glassredis.command.impl.CommandTester.bulk;
import static glassredis.command.impl.CommandTester.error;
import static glassredis.command.impl.CommandTester.integer;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ExpireCommandsTest {

    private final CommandTester tester = new CommandTester();

    // --- SET 의 만료 옵션 ---

    @Test
    @DisplayName("SET EX 로 건 만료 - TTL 은 반올림한 초, PTTL 은 밀리초")
    void setExThenTtl() {
        run("SET", "k", "v", "EX", "10");
        assertEquals(integer(10), run("TTL", "k"));

        advance(9_400); // 잔여 600ms
        assertEquals(integer(1), run("TTL", "k"), "0.6초는 버리면 0, 반올림하면 1");
        assertEquals(integer(600), run("PTTL", "k"));
    }

    @Test
    @DisplayName("만료 시각 경과 시 GET 은 nil, TTL 은 -2")
    void expiredKeyLooksMissing() {
        run("SET", "k", "v", "PX", "100");

        advance(100);
        assertEquals(bulk("v"), run("GET", "k"), "만료 시각과 같은 ms 에는 아직 살아 있다");

        advance(1);
        assertEquals(RespValue.NIL, run("GET", "k"));
        assertEquals(integer(-2), run("TTL", "k"));
    }

    @Test
    @DisplayName("옵션 없는 SET - 기존 만료 제거")
    void plainSetClearsTtl() {
        run("SET", "k", "v", "EX", "10");
        run("SET", "k", "v2");

        assertEquals(integer(-1), run("TTL", "k"));
    }

    @Test
    @DisplayName("SET KEEPTTL - 값만 교체, 기존 만료 시각 유지")
    void keepTtl() {
        run("SET", "k", "v", "EX", "10");
        advance(3_000);
        run("SET", "k", "v2", "KEEPTTL");

        assertEquals(integer(7), run("TTL", "k"));
        assertEquals(bulk("v2"), run("GET", "k"));
    }

    @Test
    @DisplayName("SET 만료 값 오류 시 종류별로 다른 에러")
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
    @DisplayName("옵션 조합 검사 후 만료 값 해석 - 문법 오류가 숫자 오류보다 우선")
    void setChecksSyntaxBeforeNumbers() {
        assertEquals(error("ERR syntax error"), run("SET", "k", "v", "EX", "ten", "BOGUS"));
        assertEquals(RespValue.NIL, run("GET", "k"));
    }

    @Test
    @DisplayName("같은 만료 옵션 중복 시 마지막 값만 해석")
    void setRepeatedExpiryUsesLast() {
        assertEquals(RespValue.OK, run("SET", "k", "v", "EX", "10", "EX", "20"));
        assertEquals(integer(20), run("TTL", "k"));

        assertEquals(RespValue.OK, run("SET", "k", "v", "EX", "ten", "EX", "30"));
        assertEquals(integer(30), run("TTL", "k"));
    }

    @Test
    @DisplayName("EXAT, PXAT - 유닉스 시각으로 만료 설정, 지난 시각이면 즉시 없는 키 취급")
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
    @DisplayName("EXPIRE 는 있는 키에만 적용, PEXPIRE 는 밀리초 단위")
    void expireAndPexpire() {
        assertEquals(integer(0), run("EXPIRE", "missing", "10"));

        run("SET", "k", "v");
        assertEquals(integer(1), run("EXPIRE", "k", "10"));
        assertEquals(integer(10), run("TTL", "k"));

        assertEquals(integer(1), run("PEXPIRE", "k", "1500"));
        assertEquals(integer(1500), run("PTTL", "k"));
    }

    @Test
    @DisplayName("EXPIRE 0 이하 - 샘플링 대기 없이 즉시 삭제")
    void expireInThePastDeletesImmediately() {
        run("SET", "k", "v");

        assertEquals(integer(1), run("EXPIRE", "k", "0"));
        assertEquals(0, tester.keyspace.size(), "메모리에서도 바로 사라져야 한다");
        assertEquals(integer(0), run("EXISTS", "k"));
    }

    @Test
    @DisplayName("EXPIRE 값·옵션 오류 시 에러 - 옵션 검사가 숫자·키 검사보다 우선")
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
    @DisplayName("EXPIRE NX 는 만료 없을 때만, XX 는 있을 때만 설정")
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
    @DisplayName("EXPIRE GT/LT 는 새 시각이 늦을 때/이를 때만 설정 - 만료 없음은 무한대 취급")
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
    @DisplayName("LT 로 과거 시각 설정 시 조건 통과 후 즉시 삭제, GT 는 만료 없는 키에서 실패")
    void expireConditionWithPastTime() {
        run("SET", "k", "v");

        assertEquals(integer(0), run("EXPIRE", "k", "-1", "GT"));
        assertEquals(integer(1), run("EXISTS", "k"));
        assertEquals(integer(1), run("EXPIRE", "k", "-1", "LT"));
        assertEquals(integer(0), run("EXISTS", "k"));
    }

    @Test
    @DisplayName("PERSIST - 만료 시각을 지웠을 때만 1")
    void persist() {
        run("SET", "k", "v", "EX", "10");

        assertEquals(integer(1), run("PERSIST", "k"));
        assertEquals(integer(-1), run("TTL", "k"));
        assertEquals(integer(0), run("PERSIST", "k"));
        assertEquals(integer(0), run("PERSIST", "missing"));
    }

    // --- 다른 명령과 만료의 관계 ---

    @Test
    @DisplayName("INCR, APPEND 등 값 수정 명령은 만료 시각 유지")
    void modifyingCommandsKeepTtl() {
        run("SET", "n", "1", "EX", "10");
        run("INCR", "n");
        run("APPEND", "n", "0");

        assertEquals(bulk("20"), run("GET", "n"));
        assertEquals(integer(10), run("TTL", "n"));
    }

    @Test
    @DisplayName("MSET - 옵션 없는 SET 처럼 기존 만료 제거")
    void msetClearsTtl() {
        run("SET", "k", "v", "EX", "10");
        run("MSET", "k", "v2");

        assertEquals(integer(-1), run("TTL", "k"));
    }

    @Test
    @DisplayName("만료된 키에 INCR 시 0 에서 새로 시작, 만료 시각도 없음")
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
