package glassredis.command.impl;

import glassredis.resp.RespValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static glassredis.command.impl.CommandTester.bulk;
import static glassredis.command.impl.CommandTester.error;
import static glassredis.command.impl.CommandTester.integer;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** OBJECT 하위 명령 - 기대값은 Redis 7.2.4 (maxmemory-policy noeviction) 응답 */
class ObjectCommandTest {

    /** OBJ_SHARED_REFCOUNT - 공유 정수 객체 */
    private static final long SHARED_REFCOUNT = Integer.MAX_VALUE;

    private final CommandTester tester = new CommandTester();

    @Test
    @DisplayName("HELP - 첫 줄은 명령 이름 대문자, 하위 명령 설명 원문, 끝은 HELP")
    void help() {
        String[] lines = {
                "OBJECT <subcommand> [<arg> [value] [opt] ...]. Subcommands are:",
                "ENCODING <key>",
                "    Return the kind of internal representation used in order to store the value",
                "    associated with a <key>.",
                "FREQ <key>",
                "    Return the access frequency index of the <key>. The returned integer is",
                "    proportional to the logarithm of the recent access frequency of the key.",
                "IDLETIME <key>",
                "    Return the idle time of the <key>, that is the approximated number of",
                "    seconds elapsed since the last access to the key.",
                "REFCOUNT <key>",
                "    Return the number of references of the value associated with the specified",
                "    <key>.",
                "HELP",
                "    Print this help.",
        };
        List<RespValue> expected = new ArrayList<>();
        for (String line : lines) {
            expected.add(new RespValue.SimpleString(line));
        }
        assertEquals(new RespValue.Array(expected), run("OBJECT", "help"));
    }

    @Test
    @DisplayName("REFCOUNT - 0 이상 10000 미만 정수 문자열은 공유 객체, 나머지는 1, 없는 키는 nil")
    void refcount() {
        run("SET", "small", "5");
        run("SET", "big", "10000");
        run("SET", "negative", "-1");
        run("INCR", "counter");
        run("SET", "e", "9999");
        run("INCR", "e");
        run("SET", "text", "hello");
        run("RPUSH", "list", "1");
        run("MSET", "m", "7");
        run("APPEND", "appended", "5");

        assertEquals(integer(SHARED_REFCOUNT), run("OBJECT", "REFCOUNT", "small"));
        assertEquals(integer(1), run("OBJECT", "REFCOUNT", "big"));
        assertEquals(integer(1), run("OBJECT", "REFCOUNT", "negative"));
        assertEquals(integer(SHARED_REFCOUNT), run("OBJECT", "REFCOUNT", "counter"));
        assertEquals(integer(1), run("OBJECT", "REFCOUNT", "e"));
        assertEquals(integer(1), run("OBJECT", "REFCOUNT", "text"));
        assertEquals(integer(1), run("OBJECT", "REFCOUNT", "list"));
        assertEquals(integer(SHARED_REFCOUNT), run("OBJECT", "REFCOUNT", "m"));
        assertEquals(integer(SHARED_REFCOUNT), run("OBJECT", "REFCOUNT", "appended"));
        assertEquals(RespValue.NIL, run("OBJECT", "REFCOUNT", "missing"));
    }

    @Test
    @DisplayName("FREQ - LFU 정책이 아니라 에러, 없는 키는 nil 이 먼저")
    void freq() {
        run("SET", "k", "v");

        assertEquals(error("ERR An LFU maxmemory policy is not selected, access frequency not tracked. "
                        + "Please note that when switching between policies at runtime LRU and LFU data "
                        + "will take some time to adjust."),
                run("OBJECT", "FREQ", "k"));
        assertEquals(RespValue.NIL, run("OBJECT", "FREQ", "missing"));
    }

    @Test
    @DisplayName("IDLETIME - 없는 키는 nil, 있는 키는 정수")
    void idletime() {
        run("SET", "k", "v");

        assertEquals(integer(0), run("OBJECT", "IDLETIME", "k"));
        assertEquals(RespValue.NIL, run("OBJECT", "IDLETIME", "missing"));
    }

    @Test
    @DisplayName("IDLETIME - 0~9999 공유 정수는 lru 를 키끼리 공유, 서버 시작 뒤 아무도 안 건드렸으면 lru 0")
    void idletimeOfSharedIntegers() {
        // 시계 1_000_000_000ms → LRU 시계 (초 & 0xFFFFFF) = 1_000_000 - Redis 에서도 SET a 5 직후 큰 값
        run("SET", "a", "5");
        assertEquals(integer(1_000_000), run("OBJECT", "IDLETIME", "a"));

        // 다른 키로 같은 공유 객체를 읽으면 a 의 IDLETIME 도 0
        run("SET", "b", "5");
        run("GET", "b");
        assertEquals(integer(0), run("OBJECT", "IDLETIME", "a"));

        // 10000 은 공유 대상 아님 - 새 객체라 0
        run("SET", "c", "10000");
        assertEquals(integer(0), run("OBJECT", "IDLETIME", "c"));
    }

    @Test
    @DisplayName("IDLETIME - 주기 작업이 LRU 시계를 올리면 증가, OBJECT 자신과 TTL·TYPE 은 접근으로 안 침, GET 은 침")
    void idletimeGrowsUntilTouched() {
        run("SET", "k", "hello");
        tester.clock.advanceMillis(7_000);
        tester.keyspace.updateLruClock();

        assertEquals(integer(7), run("OBJECT", "IDLETIME", "k"));
        run("TTL", "k");
        run("TYPE", "k");
        assertEquals(integer(7), run("OBJECT", "IDLETIME", "k"), "NOTOUCH 조회");

        run("GET", "k");
        assertEquals(integer(0), run("OBJECT", "IDLETIME", "k"));
    }

    @Test
    @DisplayName("하위 명령별 인자 수 - 에러 문구의 이름은 object|하위명령")
    void subcommandArity() {
        assertEquals(error("ERR wrong number of arguments for 'object|help' command"), run("OBJECT", "HELP", "x"));
        assertEquals(error("ERR wrong number of arguments for 'object|idletime' command"),
                run("OBJECT", "idletime"));
        assertEquals(error("ERR wrong number of arguments for 'object|refcount' command"),
                run("OBJECT", "REFCOUNT", "a", "b"));
        assertEquals(error("ERR wrong number of arguments for 'object|freq' command"), run("OBJECT", "FREQ"));
        assertEquals(error("ERR wrong number of arguments for 'object' command"), run("OBJECT"));
        assertEquals(error("ERR unknown subcommand 'foo'. Try OBJECT HELP."), run("OBJECT", "foo"));
    }

    @Test
    @DisplayName("하위 명령 이름은 C 문자열 비교 - NUL 뒤는 무시")
    void subcommandNameStopsAtNul() {
        run("SET", "k", "12");

        assertEquals(bulk("int"), run("OBJECT", "encoding\u0000x", "k"));
        assertEquals(15, ((RespValue.Array) run("OBJECT", "help\u0000x")).items().size());
    }

    private RespValue run(String... argv) {
        return tester.run(argv);
    }
}
