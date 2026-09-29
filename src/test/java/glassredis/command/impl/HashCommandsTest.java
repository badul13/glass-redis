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

class HashCommandsTest {

    private final CommandTester tester = new CommandTester();

    @Test
    @DisplayName("HSET 은 새로 생긴 필드만 센다")
    void hsetCountsNewFields() {
        assertEquals(integer(2), run("HSET", "h", "a", "1", "b", "2"));
        assertEquals(integer(1), run("HSET", "h", "a", "10", "c", "3"));

        assertEquals(bulk("10"), run("HGET", "h", "a"));
        assertEquals(integer(3), run("HLEN", "h"));
    }

    @Test
    @DisplayName("필드와 값의 짝이 안 맞으면 인자 개수 에러다")
    void hsetNeedsPairs() {
        assertEquals(error("ERR wrong number of arguments for 'hset' command"), run("HSET", "h", "a"));
        assertEquals(error("ERR wrong number of arguments for 'hset' command"), run("HSET", "h", "a", "1", "b"));
    }

    @Test
    @DisplayName("HGET 은 없는 필드와 없는 키에 nil 을 준다")
    void hgetMissing() {
        run("HSET", "h", "a", "1");

        assertEquals(RespValue.NIL, run("HGET", "h", "nope"));
        assertEquals(RespValue.NIL, run("HGET", "missing", "a"));
    }

    @Test
    @DisplayName("HMGET 은 인자 순서대로 답하고, 없는 자리는 nil 이다")
    void hmget() {
        run("HSET", "h", "a", "1", "b", "2");

        assertEquals(new RespValue.Array(List.of(bulk("2"), RespValue.NIL, bulk("1"))),
                run("HMGET", "h", "b", "nope", "a"));
        assertEquals(new RespValue.Array(List.of(RespValue.NIL, RespValue.NIL)), run("HMGET", "missing", "a", "b"));
    }

    @Test
    @DisplayName("HGETALL/HKEYS/HVALS 는 넣은 순서대로 훑는다")
    void dumps() {
        run("HSET", "h", "name", "glass", "lang", "java");

        assertEquals(array("name", "glass", "lang", "java"), run("HGETALL", "h"));
        assertEquals(array("name", "lang"), run("HKEYS", "h"));
        assertEquals(array("glass", "java"), run("HVALS", "h"));
        assertEquals(RespValue.EMPTY_ARRAY, run("HGETALL", "missing"));
    }

    @Test
    @DisplayName("HDEL 로 마지막 필드를 지우면 키가 사라진다")
    void hdelRemovesKeyWhenEmpty() {
        run("HSET", "h", "a", "1", "b", "2");

        assertEquals(integer(1), run("HDEL", "h", "a", "nope"));
        assertEquals(integer(0), run("HEXISTS", "h", "a"));
        assertEquals(integer(1), run("HEXISTS", "h", "b"));

        assertEquals(integer(1), run("HDEL", "h", "b"));
        assertEquals(integer(0), run("EXISTS", "h"));
    }

    @Test
    @DisplayName("HINCRBY 는 없는 필드를 0 에서 시작한다")
    void hincrby() {
        assertEquals(integer(5), run("HINCRBY", "h", "n", "5"));
        assertEquals(integer(2), run("HINCRBY", "h", "n", "-3"));
        assertEquals(bulk("2"), run("HGET", "h", "n"));
    }

    @Test
    @DisplayName("HINCRBY 는 필드 값과 증가량 중 어느 쪽이 틀렸는지에 따라 다른 에러를 준다")
    void hincrbyErrors() {
        run("HSET", "h", "text", "abc", "big", "9223372036854775807");

        assertEquals(error("ERR hash value is not an integer"), run("HINCRBY", "h", "text", "1"));
        assertEquals(error("ERR value is not an integer or out of range"), run("HINCRBY", "h", "n", "x"));
        assertEquals(error("ERR increment or decrement would overflow"), run("HINCRBY", "h", "big", "1"));
    }

    @Test
    @DisplayName("실패한 HINCRBY 는 빈 Hash 를 남기지 않는다")
    void failedHincrbyLeavesNoKey() {
        run("HINCRBY", "h", "n", "not-a-number");

        assertEquals(integer(0), run("EXISTS", "h"));
    }

    @Test
    @DisplayName("TYPE 은 hash 를 주고, 다른 자료형 명령은 WRONGTYPE 이다")
    void typeAndWrongType() {
        run("HSET", "h", "a", "1");
        run("SET", "s", "v");

        assertEquals(new RespValue.SimpleString("hash"), run("TYPE", "h"));
        assertEquals(error("WRONGTYPE Operation against a key holding the wrong kind of value"), run("GET", "h"));
        assertEquals(error("WRONGTYPE Operation against a key holding the wrong kind of value"),
                run("HSET", "s", "a", "1"));
        assertEquals(error("WRONGTYPE Operation against a key holding the wrong kind of value"),
                run("HMGET", "s", "a"));
    }

    private RespValue run(String... argv) {
        return tester.run(argv);
    }
}
