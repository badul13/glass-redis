package glassredis.command.impl;

import glassredis.resp.RespValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static glassredis.command.impl.CommandTester.array;
import static glassredis.command.impl.CommandTester.bulk;
import static glassredis.command.impl.CommandTester.error;
import static glassredis.command.impl.CommandTester.integer;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ListCommandsTest {

    private static final RespValue WRONGTYPE =
            error("WRONGTYPE Operation against a key holding the wrong kind of value");

    private final CommandTester tester = new CommandTester();

    @Test
    @DisplayName("LPUSH 는 원소를 하나씩 앞에 넣어서 순서가 뒤집힌다")
    void lpushReversesOrder() {
        assertEquals(integer(3), run("LPUSH", "k", "a", "b", "c"));
        assertEquals(array("c", "b", "a"), run("LRANGE", "k", "0", "-1"));
    }

    @Test
    @DisplayName("RPUSH 는 적은 순서 그대로 뒤에 붙는다")
    void rpushKeepsOrder() {
        run("RPUSH", "k", "a", "b");
        assertEquals(integer(4), run("RPUSH", "k", "c", "d"));
        assertEquals(array("a", "b", "c", "d"), run("LRANGE", "k", "0", "-1"));
    }

    @Test
    @DisplayName("LPOP/RPOP 은 양 끝에서 꺼내고, 비면 키가 사라진다")
    void popRemovesKeyWhenEmpty() {
        run("RPUSH", "k", "a", "b");

        assertEquals(bulk("a"), run("LPOP", "k"));
        assertEquals(bulk("b"), run("RPOP", "k"));
        assertEquals(integer(0), run("EXISTS", "k"));
        assertEquals(RespValue.NIL, run("LPOP", "k"));
    }

    @Test
    @DisplayName("count 를 주면 1 이어도 배열로 답하고, 있는 만큼만 꺼낸다")
    void popWithCount() {
        run("RPUSH", "k", "a", "b", "c");

        assertEquals(array("a"), run("LPOP", "k", "1"));
        assertEquals(array("c", "b"), run("RPOP", "k", "10"));
        assertEquals(integer(0), run("EXISTS", "k"));
    }

    @Test
    @DisplayName("count 가 0 이면 빈 배열, 음수면 에러다")
    void popCountBounds() {
        run("RPUSH", "k", "a");

        assertEquals(RespValue.EMPTY_ARRAY, run("LPOP", "k", "0"));
        assertEquals(error("ERR value is out of range, must be positive"), run("LPOP", "k", "-1"));
        assertEquals(integer(1), run("LLEN", "k"));
    }

    @Test
    @DisplayName("LRANGE 는 음수 인덱스를 뒤에서 세고, 범위를 넘으면 잘라서 맞춘다")
    void lrangeIndexes() {
        run("RPUSH", "k", "a", "b", "c", "d");

        assertEquals(array("c", "d"), run("LRANGE", "k", "-2", "-1"));
        assertEquals(array("b", "c", "d"), run("LRANGE", "k", "1", "10"));   // 뒤쪽에서 출발하는 경로
        assertEquals(array("a", "b"), run("LRANGE", "k", "0", "1"));         // 앞쪽에서 출발하는 경로
        assertEquals(array("a", "b", "c", "d"), run("LRANGE", "k", "-100", "100"));
        assertEquals(array("b"), run("LRANGE", "k", "1", "1"));
        assertEquals(RespValue.EMPTY_ARRAY, run("LRANGE", "k", "3", "1"));
        assertEquals(RespValue.EMPTY_ARRAY, run("LRANGE", "k", "10", "20"));
        assertEquals(RespValue.EMPTY_ARRAY, run("LRANGE", "missing", "0", "-1"));
    }

    @Test
    @DisplayName("LINDEX 는 앞뒤 어느 쪽 인덱스든 받고, 범위 밖이면 nil 이다")
    void lindex() {
        run("RPUSH", "k", "a", "b", "c", "d", "e");

        assertEquals(bulk("a"), run("LINDEX", "k", "0"));
        assertEquals(bulk("d"), run("LINDEX", "k", "3"));
        assertEquals(bulk("e"), run("LINDEX", "k", "-1"));
        assertEquals(bulk("b"), run("LINDEX", "k", "-4"));
        assertEquals(RespValue.NIL, run("LINDEX", "k", "5"));
        assertEquals(RespValue.NIL, run("LINDEX", "k", "-6"));
    }

    @Test
    @DisplayName("LREM 은 count 부호에 따라 앞에서, 뒤에서, 전부 지운다")
    void lrem() {
        run("RPUSH", "k", "x", "a", "x", "b", "x");

        assertEquals(integer(1), run("LREM", "k", "1", "x"));
        assertEquals(array("a", "x", "b", "x"), run("LRANGE", "k", "0", "-1"));

        assertEquals(integer(1), run("LREM", "k", "-1", "x"));
        assertEquals(array("a", "x", "b"), run("LRANGE", "k", "0", "-1"));

        run("RPUSH", "k", "x");
        assertEquals(integer(2), run("LREM", "k", "0", "x"));
        assertEquals(array("a", "b"), run("LRANGE", "k", "0", "-1"));
    }

    @Test
    @DisplayName("LTRIM 은 구간만 남기고, 남는 게 없으면 키를 지운다")
    void ltrim() {
        run("RPUSH", "k", "a", "b", "c", "d", "e");

        assertEquals(RespValue.OK, run("LTRIM", "k", "1", "-2"));
        assertEquals(array("b", "c", "d"), run("LRANGE", "k", "0", "-1"));

        assertEquals(RespValue.OK, run("LTRIM", "k", "5", "10"));
        assertEquals(integer(0), run("EXISTS", "k"));
    }

    @Test
    @DisplayName("TYPE 은 list 를 준다")
    void type() {
        run("RPUSH", "k", "a");
        assertEquals(new RespValue.SimpleString("list"), run("TYPE", "k"));
    }

    @Test
    @DisplayName("문자열에 List 명령을, List 에 문자열 명령을 쓰면 WRONGTYPE 이다")
    void wrongType() {
        run("SET", "s", "v");
        run("RPUSH", "l", "a");

        assertEquals(WRONGTYPE, run("LPUSH", "s", "a"));
        assertEquals(WRONGTYPE, run("LRANGE", "s", "0", "-1"));
        assertEquals(WRONGTYPE, run("GET", "l"));
        assertEquals(WRONGTYPE, run("INCR", "l"));
        assertEquals(WRONGTYPE, run("APPEND", "l", "x"));
        assertEquals(WRONGTYPE, run("SET", "l", "v", "GET"));
        // MGET 은 에러 대신 그 자리를 nil 로 채운다
        assertEquals(new RespValue.Array(List.of(bulk("v"), RespValue.NIL)), run("MGET", "s", "l"));
    }

    @Test
    @DisplayName("SET 은 자료형을 가리지 않고 덮어쓴다")
    void setOverwritesAnyType() {
        run("RPUSH", "k", "a");

        assertEquals(RespValue.OK, run("SET", "k", "v"));
        assertEquals(bulk("v"), run("GET", "k"));
    }

    @Test
    @DisplayName("만료 시각은 List 에도 걸린다")
    void listsExpire() {
        run("RPUSH", "k", "a");
        run("PEXPIRE", "k", "100");

        tester.clock.advanceMillis(101);

        assertEquals(integer(0), run("LLEN", "k"));
    }

    private RespValue run(String... argv) {
        return tester.run(argv);
    }
}
