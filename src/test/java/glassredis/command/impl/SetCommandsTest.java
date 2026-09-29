package glassredis.command.impl;

import glassredis.resp.RespValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static glassredis.command.impl.CommandTester.array;
import static glassredis.command.impl.CommandTester.error;
import static glassredis.command.impl.CommandTester.integer;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SetCommandsTest {

    private final CommandTester tester = new CommandTester();

    @Test
    @DisplayName("SADD - 중복 거부, 새로 들어간 수만 집계")
    void saddIgnoresDuplicates() {
        assertEquals(integer(2), run("SADD", "s", "a", "b", "a"));
        assertEquals(integer(1), run("SADD", "s", "b", "c"));

        assertEquals(integer(3), run("SCARD", "s"));
        assertEquals(array("a", "b", "c"), run("SMEMBERS", "s"));
    }

    @Test
    @DisplayName("SISMEMBER - 있으면 1, 없으면 0")
    void sismember() {
        run("SADD", "s", "a");

        assertEquals(integer(1), run("SISMEMBER", "s", "a"));
        assertEquals(integer(0), run("SISMEMBER", "s", "b"));
        assertEquals(integer(0), run("SISMEMBER", "missing", "a"));
    }

    @Test
    @DisplayName("SREM 으로 마지막 원소 제거 시 키 소멸")
    void sremRemovesKeyWhenEmpty() {
        run("SADD", "s", "a", "b");

        assertEquals(integer(1), run("SREM", "s", "a", "nope"));
        assertEquals(integer(1), run("SREM", "s", "b"));
        assertEquals(integer(0), run("EXISTS", "s"));
    }

    @Test
    @DisplayName("SINTER/SUNION/SDIFF - 집합 연산 결과")
    void algebra() {
        run("SADD", "x", "a", "b", "c");
        run("SADD", "y", "b", "c", "d");

        assertEquals(array("b", "c"), run("SINTER", "x", "y"));
        assertEquals(array("a", "b", "c", "d"), run("SUNION", "x", "y"));
        assertEquals(array("a"), run("SDIFF", "x", "y"));
        assertEquals(array("d"), run("SDIFF", "y", "x"));
    }

    @Test
    @DisplayName("없는 키는 빈 Set 취급")
    void missingKeyIsEmptySet() {
        run("SADD", "x", "a");

        assertEquals(RespValue.EMPTY_ARRAY, run("SINTER", "x", "missing"));
        assertEquals(array("a"), run("SUNION", "x", "missing"));
        assertEquals(array("a"), run("SDIFF", "x", "missing"));
        assertEquals(RespValue.EMPTY_ARRAY, run("SMEMBERS", "missing"));
    }

    @Test
    @DisplayName("결과가 빈 게 뻔해도 Set 이 아닌 키가 끼면 WRONGTYPE")
    void wrongTypeCheckedBeforeShortcut() {
        run("SET", "str", "v");

        assertEquals(error("WRONGTYPE Operation against a key holding the wrong kind of value"),
                run("SINTER", "missing", "str"));
        assertEquals(error("WRONGTYPE Operation against a key holding the wrong kind of value"),
                run("SADD", "str", "a"));
    }

    @Test
    @DisplayName("TYPE - set")
    void type() {
        run("SADD", "s", "a");
        assertEquals(new RespValue.SimpleString("set"), run("TYPE", "s"));
    }

    private RespValue run(String... argv) {
        return tester.run(argv);
    }
}
