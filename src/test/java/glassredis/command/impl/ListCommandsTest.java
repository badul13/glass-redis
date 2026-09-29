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
    @DisplayName("LPUSH - 하나씩 앞에 삽입되어 순서 역전")
    void lpushReversesOrder() {
        assertEquals(integer(3), run("LPUSH", "k", "a", "b", "c"));
        assertEquals(array("c", "b", "a"), run("LRANGE", "k", "0", "-1"));
    }

    @Test
    @DisplayName("RPUSH - 적은 순서 그대로 뒤에 추가")
    void rpushKeepsOrder() {
        run("RPUSH", "k", "a", "b");
        assertEquals(integer(4), run("RPUSH", "k", "c", "d"));
        assertEquals(array("a", "b", "c", "d"), run("LRANGE", "k", "0", "-1"));
    }

    @Test
    @DisplayName("LPOP/RPOP - 양 끝에서 추출, 비면 키 소멸")
    void popRemovesKeyWhenEmpty() {
        run("RPUSH", "k", "a", "b");

        assertEquals(bulk("a"), run("LPOP", "k"));
        assertEquals(bulk("b"), run("RPOP", "k"));
        assertEquals(integer(0), run("EXISTS", "k"));
        assertEquals(RespValue.NIL, run("LPOP", "k"));
    }

    @Test
    @DisplayName("count 지정 시 1 이어도 배열 응답, 있는 만큼만 추출")
    void popWithCount() {
        run("RPUSH", "k", "a", "b", "c");

        assertEquals(array("a"), run("LPOP", "k", "1"));
        assertEquals(array("c", "b"), run("RPOP", "k", "10"));
        assertEquals(integer(0), run("EXISTS", "k"));
    }

    @Test
    @DisplayName("count 가 0 이면 빈 배열, 음수면 에러")
    void popCountBounds() {
        run("RPUSH", "k", "a");

        assertEquals(RespValue.EMPTY_ARRAY, run("LPOP", "k", "0"));
        assertEquals(error("ERR value is out of range, must be positive"), run("LPOP", "k", "-1"));
        assertEquals(integer(1), run("LLEN", "k"));
    }

    @Test
    @DisplayName("LRANGE - 음수 인덱스는 뒤에서부터, 범위 초과는 잘라서 보정")
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
    @DisplayName("LINDEX - 앞뒤 인덱스 모두 허용, 범위 밖이면 nil")
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
    @DisplayName("LREM - count 부호에 따라 앞에서, 뒤에서, 전부 삭제")
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
    @DisplayName("LTRIM - 구간만 유지, 남는 게 없으면 키 삭제")
    void ltrim() {
        run("RPUSH", "k", "a", "b", "c", "d", "e");

        assertEquals(RespValue.OK, run("LTRIM", "k", "1", "-2"));
        assertEquals(array("b", "c", "d"), run("LRANGE", "k", "0", "-1"));

        assertEquals(RespValue.OK, run("LTRIM", "k", "5", "10"));
        assertEquals(integer(0), run("EXISTS", "k"));
    }

    @Test
    @DisplayName("TYPE - list")
    void type() {
        run("RPUSH", "k", "a");
        assertEquals(new RespValue.SimpleString("list"), run("TYPE", "k"));
    }

    @Test
    @DisplayName("문자열에 List 명령, List 에 문자열 명령 사용 시 WRONGTYPE")
    void wrongType() {
        run("SET", "s", "v");
        run("RPUSH", "l", "a");

        assertEquals(WRONGTYPE, run("LPUSH", "s", "a"));
        assertEquals(WRONGTYPE, run("LRANGE", "s", "0", "-1"));
        assertEquals(WRONGTYPE, run("GET", "l"));
        assertEquals(WRONGTYPE, run("INCR", "l"));
        assertEquals(WRONGTYPE, run("APPEND", "l", "x"));
        assertEquals(WRONGTYPE, run("SET", "l", "v", "GET"));
        // MGET - 에러 대신 그 자리에 nil
        assertEquals(new RespValue.Array(List.of(bulk("v"), RespValue.NIL)), run("MGET", "s", "l"));
    }

    @Test
    @DisplayName("SET - 자료형 무관하게 덮어쓰기")
    void setOverwritesAnyType() {
        run("RPUSH", "k", "a");

        assertEquals(RespValue.OK, run("SET", "k", "v"));
        assertEquals(bulk("v"), run("GET", "k"));
    }

    @Test
    @DisplayName("List 에도 만료 시각 적용")
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
