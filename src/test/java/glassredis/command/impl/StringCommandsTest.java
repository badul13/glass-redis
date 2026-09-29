package glassredis.command.impl;

import glassredis.resp.RespValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static glassredis.command.impl.CommandTester.bulk;
import static glassredis.command.impl.CommandTester.error;
import static glassredis.command.impl.CommandTester.integer;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** 만료 관련은 {@link ExpireCommandsTest} 참고 */
class StringCommandsTest {

    private final CommandTester tester = new CommandTester();

    // --- GET / SET / DEL / EXISTS ---

    @Test
    @DisplayName("SET 후 GET 조회")
    void setThenGet() {
        assertEquals(RespValue.OK, run("SET", "k", "v"));
        assertEquals(bulk("v"), run("GET", "k"));
    }

    @Test
    @DisplayName("없는 키의 GET 은 nil - 빈 문자열 값과 구별")
    void getMissingIsNilNotEmpty() {
        assertEquals(RespValue.NIL, run("GET", "missing"));

        run("SET", "empty", "");
        assertEquals(bulk(""), run("GET", "empty"));
    }

    @Test
    @DisplayName("SET - 기존 값 덮어쓰기")
    void setOverwrites() {
        run("SET", "k", "old");
        run("SET", "k", "new");
        assertEquals(bulk("new"), run("GET", "k"));
    }

    @Test
    @DisplayName("DEL - 실제로 지운 개수만 집계")
    void delCountsOnlyExistingKeys() {
        run("SET", "a", "1");
        run("SET", "b", "2");

        assertEquals(integer(2), run("DEL", "a", "b", "never-existed"));
        assertEquals(RespValue.NIL, run("GET", "a"));
    }

    @Test
    @DisplayName("EXISTS - 같은 키를 여러 번 적으면 그 횟수만큼 집계")
    void existsCountsDuplicates() {
        run("SET", "k", "v");

        assertEquals(integer(2), run("EXISTS", "k", "k", "missing"));
    }

    @Test
    @DisplayName("TYPE - 문자열은 string, 없는 키는 none")
    void type() {
        run("SET", "k", "v");

        assertEquals(new RespValue.SimpleString("string"), run("TYPE", "k"));
        assertEquals(new RespValue.SimpleString("none"), run("TYPE", "missing"));
    }

    @Test
    @DisplayName("인자 개수 오류 시 에러")
    void wrongArity() {
        assertEquals(error("ERR wrong number of arguments for 'get' command"), run("GET"));
        assertEquals(error("ERR wrong number of arguments for 'set' command"), run("SET", "k"));
        assertEquals(error("ERR wrong number of arguments for 'del' command"), run("DEL"));
        assertEquals(error("ERR wrong number of arguments for 'mset' command"), run("MSET", "k"));
        assertEquals(error("ERR wrong number of arguments for 'incrby' command"), run("INCRBY", "k"));
    }

    // --- SET 옵션 (만료 제외) ---

    @Test
    @DisplayName("SET NX - 키가 없을 때만 쓰기, 못 쓰면 nil")
    void setNx() {
        assertEquals(RespValue.OK, run("SET", "k", "first", "NX"));
        assertEquals(RespValue.NIL, run("SET", "k", "second", "nx"));
        assertEquals(bulk("first"), run("GET", "k"));
    }

    @Test
    @DisplayName("SET XX - 키가 있을 때만 쓰기")
    void setXx() {
        assertEquals(RespValue.NIL, run("SET", "k", "v", "XX"));
        assertEquals(RespValue.NIL, run("GET", "k"));

        run("SET", "k", "v");
        assertEquals(RespValue.OK, run("SET", "k", "v2", "XX"));
        assertEquals(bulk("v2"), run("GET", "k"));
    }

    @Test
    @DisplayName("SET GET - OK 대신 쓰기 전 값 응답, NX 로 못 써도 기존 값 응답")
    void setGet() {
        assertEquals(RespValue.NIL, run("SET", "k", "v1", "GET"));
        assertEquals(bulk("v1"), run("SET", "k", "v2", "GET"));
        assertEquals(bulk("v2"), run("SET", "k", "v3", "NX", "GET"));
        assertEquals(bulk("v2"), run("GET", "k"));
    }

    @Test
    @DisplayName("SET 옵션 충돌이나 모르는 옵션이면 syntax error - 키는 그대로")
    void setSyntaxErrors() {
        assertEquals(error("ERR syntax error"), run("SET", "k", "v", "NX", "XX"));
        assertEquals(error("ERR syntax error"), run("SET", "k", "v", "BOGUS"));
        assertEquals(RespValue.NIL, run("GET", "k"));
    }

    @Test
    @DisplayName("같은 SET 옵션 중복 허용")
    void setRepeatedOptions() {
        assertEquals(RespValue.OK, run("SET", "k", "v", "NX", "nx"));
        assertEquals(bulk("v"), run("SET", "k", "v2", "GET", "GET"));
        assertEquals(bulk("v2"), run("GET", "k"));
    }

    // --- INCR 계열 ---

    @Test
    @DisplayName("INCR - 없는 키는 0 에서 시작, 결과는 문자열로 저장")
    void incrStartsFromZero() {
        assertEquals(integer(1), run("INCR", "n"));
        assertEquals(integer(2), run("INCR", "n"));
        assertEquals(bulk("2"), run("GET", "n"));
    }

    @Test
    @DisplayName("DECR, INCRBY, DECRBY - 음수 결과도 그대로 처리")
    void otherIncrements() {
        assertEquals(integer(-1), run("DECR", "n"));
        assertEquals(integer(9), run("INCRBY", "n", "10"));
        assertEquals(integer(-91), run("DECRBY", "n", "100"));
        assertEquals(integer(-81), run("INCRBY", "n", "10"));
    }

    @Test
    @DisplayName("값이나 증가량이 정수가 아니면 에러 - 값은 그대로")
    void incrOnNonInteger() {
        run("SET", "word", "hello");
        run("SET", "padded", "007");

        assertEquals(error("ERR value is not an integer or out of range"), run("INCR", "word"));
        assertEquals(error("ERR value is not an integer or out of range"), run("INCR", "padded"));
        assertEquals(error("ERR value is not an integer or out of range"), run("INCRBY", "n", "1.5"));
        assertEquals(bulk("hello"), run("GET", "word"));
    }

    @Test
    @DisplayName("long 범위 초과 시 오버플로 에러 - 값은 그대로")
    void incrOverflow() {
        run("SET", "max", "9223372036854775807");

        assertEquals(error("ERR increment or decrement would overflow"), run("INCR", "max"));
        assertEquals(bulk("9223372036854775807"), run("GET", "max"));
        assertEquals(error("ERR decrement would overflow"), run("DECRBY", "n", "-9223372036854775808"));
    }

    // --- MGET / MSET / APPEND / STRLEN ---

    @Test
    @DisplayName("MSET 후 MGET 조회 - 없는 키 자리는 nil")
    void msetAndMget() {
        assertEquals(RespValue.OK, run("MSET", "a", "1", "b", "2"));

        assertEquals(RespValue.Array.of(bulk("1"), RespValue.NIL, bulk("2")), run("MGET", "a", "missing", "b"));
    }

    @Test
    @DisplayName("APPEND - 없는 키는 새로 생성, 있으면 이어 붙인 뒤 길이 응답")
    void append() {
        assertEquals(integer(5), run("APPEND", "k", "hello"));
        assertEquals(integer(11), run("APPEND", "k", " world"));
        assertEquals(bulk("hello world"), run("GET", "k"));
    }

    @Test
    @DisplayName("STRLEN - 문자 수가 아닌 바이트 수, 없는 키는 0")
    void strlen() {
        run("SET", "k", "한글");

        assertEquals(integer(6), run("STRLEN", "k"));
        assertEquals(integer(0), run("STRLEN", "missing"));
    }

    private RespValue run(String... argv) {
        return tester.run(argv);
    }
}
