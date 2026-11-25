package glassredis.command.impl;

import glassredis.resp.RespValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static glassredis.command.impl.CommandTester.array;
import static glassredis.command.impl.CommandTester.bulk;
import static glassredis.command.impl.CommandTester.error;
import static glassredis.command.impl.CommandTester.integer;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SortedSetCommandsTest {

    private final CommandTester tester = new CommandTester();

    // 점수 순: carol(10) < alice(20) < bob(30) < dave(40)
    @BeforeEach
    void leaderboard() {
        run("ZADD", "board", "20", "alice", "30", "bob", "10", "carol", "40", "dave");
    }

    // --- ZADD ---

    @Test
    @DisplayName("ZADD - 새 멤버 수만 집계, 기존 멤버는 점수만 갱신")
    void zaddCountsNewMembers() {
        assertEquals(integer(1), run("ZADD", "board", "50", "alice", "5", "erin"));

        assertEquals(bulk("50"), run("ZSCORE", "board", "alice"));
        assertEquals(integer(5), run("ZCARD", "board"));
    }

    @Test
    @DisplayName("CH 지정 시 점수가 바뀐 멤버까지 집계")
    void zaddCh() {
        assertEquals(integer(2), run("ZADD", "board", "CH", "21", "alice", "30", "bob", "1", "erin"));
    }

    @Test
    @DisplayName("NX 는 새 멤버만, XX 는 기존 멤버만 대상")
    void zaddNxXx() {
        assertEquals(integer(1), run("ZADD", "board", "NX", "99", "alice", "1", "erin"));
        assertEquals(bulk("20"), run("ZSCORE", "board", "alice"));

        assertEquals(integer(0), run("ZADD", "board", "XX", "99", "alice", "1", "frank"));
        assertEquals(bulk("99"), run("ZSCORE", "board", "alice"));
        assertEquals(RespValue.NIL, run("ZSCORE", "board", "frank"));
    }

    @Test
    @DisplayName("GT 는 점수가 오를 때만, LT 는 내릴 때만 갱신")
    void zaddGtLt() {
        run("ZADD", "board", "GT", "5", "alice");
        assertEquals(bulk("20"), run("ZSCORE", "board", "alice"));
        run("ZADD", "board", "GT", "25", "alice");
        assertEquals(bulk("25"), run("ZSCORE", "board", "alice"));

        run("ZADD", "board", "LT", "50", "bob");
        assertEquals(bulk("30"), run("ZSCORE", "board", "bob"));
    }

    @Test
    @DisplayName("INCR - 더한 결과 응답, 조건에 막히면 nil")
    void zaddIncr() {
        assertEquals(bulk("25.5"), run("ZADD", "board", "INCR", "5.5", "alice"));
        assertEquals(RespValue.NIL, run("ZADD", "board", "NX", "INCR", "1", "alice"));
    }

    @Test
    @DisplayName("ZADD 옵션 조합과 점수 오류")
    void zaddErrors() {
        assertEquals(error("ERR XX and NX options at the same time are not compatible"),
                run("ZADD", "z", "NX", "XX", "1", "a"));
        assertEquals(error("ERR GT, LT, and/or NX options at the same time are not compatible"),
                run("ZADD", "z", "GT", "LT", "1", "a"));
        assertEquals(error("ERR INCR option supports a single increment-element pair"),
                run("ZADD", "z", "INCR", "1", "a", "2", "b"));
        assertEquals(error("ERR syntax error"), run("ZADD", "z", "1", "a", "2"));
        assertEquals(error("ERR value is not a valid float"), run("ZADD", "z", "1", "a", "x", "b"));
        // 점수 하나만 틀려도 앞쪽 멤버까지 미반영
        assertEquals(integer(0), run("EXISTS", "z"));
    }

    @Test
    @DisplayName("없는 키에 ZADD XX 시 키 생성 없음")
    void zaddXxOnMissingKey() {
        assertEquals(integer(0), run("ZADD", "z", "XX", "1", "a"));
        assertEquals(integer(0), run("EXISTS", "z"));
    }

    // --- 순위 ---

    @Test
    @DisplayName("ZRANK 는 낮은 점수부터, ZREVRANK 는 높은 점수부터 0")
    void ranks() {
        assertEquals(integer(0), run("ZRANK", "board", "carol"));
        assertEquals(integer(3), run("ZRANK", "board", "dave"));
        assertEquals(integer(0), run("ZREVRANK", "board", "dave"));
        assertEquals(RespValue.NIL, run("ZRANK", "board", "nobody"));
    }

    @Test
    @DisplayName("ZINCRBY 로 점수 상승 시 순위 변동")
    void zincrbyMovesMember() {
        assertEquals(bulk("45"), run("ZINCRBY", "board", "35", "carol"));

        assertEquals(integer(3), run("ZRANK", "board", "carol"));
        assertEquals(array("alice", "bob", "dave", "carol"), run("ZRANGE", "board", "0", "-1"));
    }

    @Test
    @DisplayName("무한대끼리 더해 NaN 이면 에러, 점수는 그대로")
    void zincrbyNaN() {
        run("ZADD", "z", "inf", "a");

        assertEquals(error("ERR resulting score is not a number (NaN)"), run("ZINCRBY", "z", "-inf", "a"));
        assertEquals(bulk("inf"), run("ZSCORE", "z", "a"));
    }

    // --- 순위 구간 ---

    @Test
    @DisplayName("ZRANGE - 순위 구간 응답, WITHSCORES 면 점수 포함")
    void zrangeByRank() {
        assertEquals(array("carol", "alice", "bob", "dave"), run("ZRANGE", "board", "0", "-1"));
        assertEquals(array("bob", "dave"), run("ZRANGE", "board", "-2", "-1"));
        assertEquals(array("carol", "10", "alice", "20"), run("ZRANGE", "board", "0", "1", "WITHSCORES"));
        assertEquals(RespValue.EMPTY_ARRAY, run("ZRANGE", "board", "5", "10"));
    }

    @Test
    @DisplayName("REV 와 ZREVRANGE - 높은 점수부터")
    void zrangeReverse() {
        assertEquals(array("dave", "bob"), run("ZREVRANGE", "board", "0", "1"));
        assertEquals(array("dave", "bob"), run("ZRANGE", "board", "0", "1", "REV"));
        assertEquals(array("alice", "carol"), run("ZREVRANGE", "board", "-2", "-1"));
    }

    // --- 점수 구간 ---

    @Test
    @DisplayName("ZRANGEBYSCORE - 점수 구간 응답, ( 를 붙인 끝은 제외")
    void zrangeByScore() {
        assertEquals(array("alice", "bob"), run("ZRANGEBYSCORE", "board", "20", "30"));
        assertEquals(array("bob"), run("ZRANGEBYSCORE", "board", "(20", "30"));
        assertEquals(array("carol", "alice"), run("ZRANGEBYSCORE", "board", "-inf", "(30"));
        assertEquals(array("bob", "dave"), run("ZRANGE", "board", "25", "+inf", "BYSCORE"));
    }

    @Test
    @DisplayName("역순 점수 구간 조회 시 max 가 먼저")
    void zrevrangeByScore() {
        assertEquals(array("bob", "alice"), run("ZREVRANGEBYSCORE", "board", "30", "20"));
        assertEquals(array("bob", "alice"), run("ZRANGE", "board", "30", "20", "BYSCORE", "REV"));
        assertEquals(RespValue.EMPTY_ARRAY, run("ZREVRANGEBYSCORE", "board", "20", "30"));
    }

    @Test
    @DisplayName("LIMIT - 건너뛸 수와 받을 수 지정, 점수 구간에서만 사용 가능")
    void limit() {
        assertEquals(array("alice", "bob"), run("ZRANGEBYSCORE", "board", "-inf", "+inf", "LIMIT", "1", "2"));
        assertEquals(array("alice", "bob", "dave"), run("ZRANGEBYSCORE", "board", "-inf", "+inf", "LIMIT", "1", "-1"));
        assertEquals(array("bob", "alice"), run("ZREVRANGEBYSCORE", "board", "+inf", "-inf", "LIMIT", "1", "2"));
        assertEquals(error("ERR syntax error, LIMIT is only supported in combination with either BYSCORE or BYLEX"),
                run("ZRANGE", "board", "0", "-1", "LIMIT", "1", "2"));
    }

    @Test
    @DisplayName("LIMIT 의 큰 offset·count - 덧셈 넘침 없이 Redis 와 같은 결과")
    void limitWithHugeOffsetOrCount() {
        // Redis 7.2.4 - offset 이 크기 이상이면 빈 배열, count 가 크면 끝까지
        assertEquals(RespValue.EMPTY_ARRAY,
                run("ZRANGEBYSCORE", "board", "30", "+inf", "LIMIT", "9223372036854775807", "1"));
        assertEquals(array("bob", "dave"),
                run("ZRANGEBYSCORE", "board", "-inf", "+inf", "LIMIT", "2", "9223372036854775807"));
        assertEquals(array("alice", "carol"),
                run("ZREVRANGEBYSCORE", "board", "+inf", "-inf", "LIMIT", "2", "9223372036854775807"));
        assertEquals(RespValue.EMPTY_ARRAY,
                run("ZRANGEBYSCORE", "board", "-inf", "+inf", "LIMIT", "4", "1"));
        assertEquals(RespValue.EMPTY_ARRAY,
                run("ZRANGEBYSCORE", "board", "-inf", "+inf", "LIMIT", "-1", "1"));
        assertEquals(array("dave"), run("ZRANGEBYSCORE", "board", "-inf", "+inf", "LIMIT", "3", "-5"));
    }

    @Test
    @DisplayName("순위 방식 LIMIT 거부 기준은 count 가 -1 이 아님 - offset 은 무관")
    void limitOnRankRangeDependsOnCountOnly() {
        assertEquals(array("carol", "alice", "bob", "dave"), run("ZRANGE", "board", "0", "-1", "LIMIT", "5", "-1"));
        assertEquals(array("dave", "bob", "alice", "carol"), run("ZREVRANGE", "board", "0", "-1", "LIMIT", "0", "-1"));
        assertEquals(error("ERR syntax error, LIMIT is only supported in combination with either BYSCORE or BYLEX"),
                run("ZRANGE", "board", "0", "-1", "LIMIT", "0", "1"));
    }

    @Test
    @DisplayName("BYSCORE·REV 두 번 지정, 이름에 이미 정해진 방식 재지정 시 syntax error")
    void repeatedModeOptions() {
        assertEquals(error("ERR syntax error"), run("ZRANGE", "board", "0", "-1", "BYSCORE", "BYSCORE"));
        assertEquals(error("ERR syntax error"), run("ZRANGE", "board", "0", "-1", "REV", "REV"));
        assertEquals(error("ERR syntax error"), run("ZRANGEBYSCORE", "board", "0", "1", "BYSCORE"));
        assertEquals(error("ERR syntax error"), run("ZREVRANGE", "board", "0", "1", "REV"));
    }

    @Test
    @DisplayName("ZRANK WITHSCORE - 순위와 점수 배열, 없으면 널 배열, 다른 단어는 syntax error")
    void zrankWithScore() {
        assertEquals(new RespValue.Array(List.of(integer(1), bulk("20"))),
                run("ZRANK", "board", "alice", "WITHSCORE"));
        assertEquals(new RespValue.Array(List.of(integer(2), bulk("20"))),
                run("ZREVRANK", "board", "alice", "withscore"));
        assertEquals(RespValue.NIL_ARRAY, run("ZRANK", "board", "nobody", "WITHSCORE"));
        assertEquals(RespValue.NIL_ARRAY, run("ZRANK", "missing", "a", "WITHSCORE"));
        assertEquals(RespValue.NIL, run("ZRANK", "missing", "a"));
        assertEquals(error("ERR syntax error"), run("ZRANK", "board", "alice", "foo"));
        assertEquals(error("ERR syntax error"), run("ZRANK", "missing", "alice", "foo"));
        assertEquals(error("ERR wrong number of arguments for 'zrank' command"),
                run("ZRANK", "board", "alice", "WITHSCORE", "x"));
        run("SET", "s", "v");
        assertEquals(error("WRONGTYPE Operation against a key holding the wrong kind of value"),
                run("ZRANK", "s", "a", "WITHSCORE"));
        assertEquals(error("ERR syntax error"), run("ZRANK", "s", "a", "foo"));
    }

    @Test
    @DisplayName("ZINCRBY 는 ZADD INCR 와 같은 경로 - 증가량 자리의 옵션 단어는 syntax error")
    void zincrbyRejectsOptionWords() {
        assertEquals(error("ERR syntax error"), run("ZINCRBY", "k", "nx", "m"));
        assertEquals(error("ERR syntax error"), run("ZINCRBY", "k", "INCR", "m"));
        assertEquals(bulk("1"), run("ZINCRBY", "k", "1", "nx"));
    }

    @Test
    @DisplayName("ZADD XX+GT - 기존 멤버를 올릴 때만, 새 멤버는 추가 안 함")
    void zaddXxGt() {
        assertEquals(integer(1), run("ZADD", "board", "XX", "GT", "CH", "5", "alice", "35", "bob", "1", "erin"));
        assertEquals(bulk("20"), run("ZSCORE", "board", "alice"));
        assertEquals(bulk("35"), run("ZSCORE", "board", "bob"));
        assertEquals(RespValue.NIL, run("ZSCORE", "board", "erin"));
    }

    @Test
    @DisplayName("ZADD INCR+XX 없는 키는 nil, INCR 결과 NaN 은 에러")
    void zaddIncrEdgeCases() {
        assertEquals(RespValue.NIL, run("ZADD", "missing", "XX", "INCR", "1", "a"));
        assertEquals(integer(0), run("EXISTS", "missing"));

        run("ZADD", "z", "+inf", "a");
        assertEquals(error("ERR resulting score is not a number (NaN)"), run("ZADD", "z", "INCR", "-inf", "a"));
        assertEquals(bulk("inf"), run("ZSCORE", "z", "a"));
    }

    @Test
    @DisplayName("ZCOUNT 구간 해석은 strtod 기준 - 빈 문자열, '(' 단독, 앞 공백, 넘치는 지수 허용")
    void zcountLenientBounds() {
        // Redis 7.2.4 zslParseRange - eptr 끝 확인과 NaN 거부만
        assertEquals(integer(0), run("ZCOUNT", "board", "", ""));
        assertEquals(integer(4), run("ZCOUNT", "board", "(", "50"));
        assertEquals(integer(4), run("ZCOUNT", "board", " 1", "50"));
        assertEquals(integer(0), run("ZCOUNT", "board", "1e400", "+inf"));
        assertEquals(integer(4), run("ZCOUNT", "board", "-1e400", "+inf"));
        assertEquals(integer(1), run("ZCOUNT", "board", "0x1e", "(0x28"));
        assertEquals(error("ERR min or max is not a float"), run("ZCOUNT", "board", "1 ", "50"));
        assertEquals(error("ERR min or max is not a float"), run("ZCOUNT", "board", "nan", "50"));
        assertEquals(error("ERR min or max is not a float"), run("ZRANGEBYSCORE", "board", "1\u0001", "50"));
    }

    @Test
    @DisplayName("ZCOUNT - 구간에 든 멤버 수")
    void zcount() {
        assertEquals(integer(4), run("ZCOUNT", "board", "-inf", "+inf"));
        assertEquals(integer(2), run("ZCOUNT", "board", "15", "35"));
        assertEquals(integer(1), run("ZCOUNT", "board", "(20", "(40"));
        assertEquals(integer(0), run("ZCOUNT", "board", "41", "50"));
        assertEquals(error("ERR min or max is not a float"), run("ZCOUNT", "board", "a", "5"));
    }

    // --- 지우기와 자료형 ---

    @Test
    @DisplayName("ZREM 으로 마지막 멤버 제거 시 키 소멸")
    void zremRemovesKeyWhenEmpty() {
        assertEquals(integer(2), run("ZREM", "board", "alice", "bob", "nobody"));
        assertEquals(array("carol", "dave"), run("ZRANGE", "board", "0", "-1"));

        run("ZREM", "board", "carol", "dave");
        assertEquals(integer(0), run("EXISTS", "board"));
    }

    @Test
    @DisplayName("TYPE 은 zset, 다른 자료형 명령은 WRONGTYPE")
    void typeAndWrongType() {
        run("SET", "s", "v");

        assertEquals(new RespValue.SimpleString("zset"), run("TYPE", "board"));
        assertEquals(error("WRONGTYPE Operation against a key holding the wrong kind of value"),
                run("ZADD", "s", "1", "a"));
        assertEquals(error("WRONGTYPE Operation against a key holding the wrong kind of value"),
                run("SMEMBERS", "board"));
    }

    private RespValue run(String... argv) {
        return tester.run(argv);
    }
}
