package glassredis.command.impl;

import glassredis.resp.RespValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static glassredis.command.impl.CommandTester.bulk;
import static glassredis.command.impl.CommandTester.error;
import static glassredis.command.impl.CommandTester.integer;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** 만료와 상관없는 문자열 명령들. 만료 쪽은 {@link ExpireCommandsTest}. */
class StringCommandsTest {

    private final CommandTester tester = new CommandTester();

    // --- GET / SET / DEL / EXISTS ---

    @Test
    @DisplayName("SET 한 값을 GET 으로 읽는다")
    void setThenGet() {
        assertEquals(RespValue.OK, run("SET", "k", "v"));
        assertEquals(bulk("v"), run("GET", "k"));
    }

    @Test
    @DisplayName("없는 키의 GET 은 nil 이고, 빈 문자열 값과는 다르다")
    void getMissingIsNilNotEmpty() {
        assertEquals(RespValue.NIL, run("GET", "missing"));

        run("SET", "empty", "");
        assertEquals(bulk(""), run("GET", "empty"));
    }

    @Test
    @DisplayName("SET 은 기존 값을 덮어쓴다")
    void setOverwrites() {
        run("SET", "k", "old");
        run("SET", "k", "new");
        assertEquals(bulk("new"), run("GET", "k"));
    }

    @Test
    @DisplayName("DEL 은 실제로 지운 개수만 센다")
    void delCountsOnlyExistingKeys() {
        run("SET", "a", "1");
        run("SET", "b", "2");

        assertEquals(integer(2), run("DEL", "a", "b", "never-existed"));
        assertEquals(RespValue.NIL, run("GET", "a"));
    }

    @Test
    @DisplayName("EXISTS 는 같은 키를 여러 번 적으면 여러 번 센다")
    void existsCountsDuplicates() {
        run("SET", "k", "v");

        assertEquals(integer(2), run("EXISTS", "k", "k", "missing"));
    }

    @Test
    @DisplayName("인자 개수가 틀리면 에러를 준다")
    void wrongArity() {
        assertEquals(error("ERR wrong number of arguments for 'get' command"), run("GET"));
        assertEquals(error("ERR wrong number of arguments for 'set' command"), run("SET", "k"));
        assertEquals(error("ERR wrong number of arguments for 'del' command"), run("DEL"));
        assertEquals(error("ERR wrong number of arguments for 'mset' command"), run("MSET", "k"));
        assertEquals(error("ERR wrong number of arguments for 'incrby' command"), run("INCRBY", "k"));
    }

    // --- SET 옵션 (만료 제외) ---

    @Test
    @DisplayName("SET NX 는 키가 없을 때만 쓰고, 못 썼으면 nil 을 준다")
    void setNx() {
        assertEquals(RespValue.OK, run("SET", "k", "first", "NX"));
        assertEquals(RespValue.NIL, run("SET", "k", "second", "nx"));
        assertEquals(bulk("first"), run("GET", "k"));
    }

    @Test
    @DisplayName("SET XX 는 키가 있을 때만 쓴다")
    void setXx() {
        assertEquals(RespValue.NIL, run("SET", "k", "v", "XX"));
        assertEquals(RespValue.NIL, run("GET", "k"));

        run("SET", "k", "v");
        assertEquals(RespValue.OK, run("SET", "k", "v2", "XX"));
        assertEquals(bulk("v2"), run("GET", "k"));
    }

    @Test
    @DisplayName("SET GET 은 OK 대신 쓰기 전의 값을 준다. NX 로 못 썼어도 기존 값은 준다")
    void setGet() {
        assertEquals(RespValue.NIL, run("SET", "k", "v1", "GET"));
        assertEquals(bulk("v1"), run("SET", "k", "v2", "GET"));
        assertEquals(bulk("v2"), run("SET", "k", "v3", "NX", "GET"));
        assertEquals(bulk("v2"), run("GET", "k"));
    }

    @Test
    @DisplayName("SET 옵션이 서로 부딪히거나 모르는 옵션이면 syntax error, 키는 건드리지 않는다")
    void setSyntaxErrors() {
        assertEquals(error("ERR syntax error"), run("SET", "k", "v", "NX", "XX"));
        assertEquals(error("ERR syntax error"), run("SET", "k", "v", "BOGUS"));
        assertEquals(RespValue.NIL, run("GET", "k"));
    }

    @Test
    @DisplayName("같은 SET 옵션을 두 번 주는 건 허용한다")
    void setRepeatedOptions() {
        assertEquals(RespValue.OK, run("SET", "k", "v", "NX", "nx"));
        assertEquals(bulk("v"), run("SET", "k", "v2", "GET", "GET"));
        assertEquals(bulk("v2"), run("GET", "k"));
    }

    // --- INCR 계열 ---

    @Test
    @DisplayName("INCR 은 없는 키를 0 으로 보고 시작하며, 결과를 문자열로 저장한다")
    void incrStartsFromZero() {
        assertEquals(integer(1), run("INCR", "n"));
        assertEquals(integer(2), run("INCR", "n"));
        assertEquals(bulk("2"), run("GET", "n"));
    }

    @Test
    @DisplayName("DECR, INCRBY, DECRBY 는 음수 결과도 그대로 다룬다")
    void otherIncrements() {
        assertEquals(integer(-1), run("DECR", "n"));
        assertEquals(integer(9), run("INCRBY", "n", "10"));
        assertEquals(integer(-91), run("DECRBY", "n", "100"));
        assertEquals(integer(-81), run("INCRBY", "n", "10"));
    }

    @Test
    @DisplayName("값이나 증가량이 정수가 아니면 에러를 주고 값은 그대로 둔다")
    void incrOnNonInteger() {
        run("SET", "word", "hello");
        run("SET", "padded", "007");

        assertEquals(error("ERR value is not an integer or out of range"), run("INCR", "word"));
        assertEquals(error("ERR value is not an integer or out of range"), run("INCR", "padded"));
        assertEquals(error("ERR value is not an integer or out of range"), run("INCRBY", "n", "1.5"));
        assertEquals(bulk("hello"), run("GET", "word"));
    }

    @Test
    @DisplayName("long 범위를 넘으면 오버플로 에러를 주고 값은 그대로 둔다")
    void incrOverflow() {
        run("SET", "max", "9223372036854775807");

        assertEquals(error("ERR increment or decrement would overflow"), run("INCR", "max"));
        assertEquals(bulk("9223372036854775807"), run("GET", "max"));
        assertEquals(error("ERR decrement would overflow"), run("DECRBY", "n", "-9223372036854775808"));
    }

    // --- MGET / MSET / APPEND / STRLEN ---

    @Test
    @DisplayName("MSET 으로 쓴 값을 MGET 으로 읽고, 없는 키 자리에는 nil 이 들어간다")
    void msetAndMget() {
        assertEquals(RespValue.OK, run("MSET", "a", "1", "b", "2"));

        assertEquals(RespValue.Array.of(bulk("1"), RespValue.NIL, bulk("2")), run("MGET", "a", "missing", "b"));
    }

    @Test
    @DisplayName("APPEND 는 없는 키를 새로 만들고, 있으면 이어 붙여 길이를 준다")
    void append() {
        assertEquals(integer(5), run("APPEND", "k", "hello"));
        assertEquals(integer(11), run("APPEND", "k", " world"));
        assertEquals(bulk("hello world"), run("GET", "k"));
    }

    @Test
    @DisplayName("STRLEN 은 문자 수가 아니라 바이트 수를 주고, 없는 키는 0 이다")
    void strlen() {
        run("SET", "k", "한글");

        assertEquals(integer(6), run("STRLEN", "k"));
        assertEquals(integer(0), run("STRLEN", "missing"));
    }

    private RespValue run(String... argv) {
        return tester.run(argv);
    }
}
