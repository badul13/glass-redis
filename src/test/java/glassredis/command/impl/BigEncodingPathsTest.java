package glassredis.command.impl;

import glassredis.resp.RespValue;
import glassredis.store.HashValue;
import glassredis.store.Key;
import glassredis.store.SetValue;
import glassredis.store.encoding.Dict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static glassredis.command.impl.CommandTester.array;
import static glassredis.command.impl.CommandTester.bulk;
import static glassredis.command.impl.CommandTester.integer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 큰 인코딩(skiplist·hashtable) 쪽 명령 경로 - 작은 인코딩 테스트만으로는 안 지나는 길
 * 기대값은 같은 명령을 Redis 7.2.4 에 보낸 응답
 */
class BigEncodingPathsTest {

    private final CommandTester tester = new CommandTester();

    @Test
    @DisplayName("skiplist - 점수 구간·순위·역순 조회, 동점은 멤버 사전순")
    void skiplistQueries() {
        List<String> zadd = new ArrayList<>(List.of("ZADD", "z"));
        for (int i = 0; i < 200; i++) {
            zadd.add(Integer.toString(i));
            zadd.add("m" + i);
        }
        assertEquals(integer(200), run(zadd.toArray(String[]::new)));
        assertEquals(integer(2), run("ZADD", "z", "100", "a100", "100", "z100"));
        assertEquals(bulk("skiplist"), run("OBJECT", "ENCODING", "z"));

        assertEquals(array("m10", "m11", "m12", "m13", "m14", "m15"), run("ZRANGEBYSCORE", "z", "10", "15"));
        assertEquals(array("m12", "m13"), run("ZRANGEBYSCORE", "z", "(10", "15", "LIMIT", "1", "2"));
        assertEquals(array("m15", "m14", "m13", "m12", "m11", "m10"), run("ZREVRANGEBYSCORE", "z", "15", "10"));
        assertEquals(array("a100", "m100", "z100"), run("ZRANGEBYSCORE", "z", "100", "100"));
        assertEquals(integer(152), run("ZRANK", "z", "m150"));
        assertEquals(integer(49), run("ZREVRANK", "z", "m150"));
        assertEquals(integer(102), run("ZRANK", "z", "z100"));
        assertEquals(array("m199", "199", "m198", "198", "m197", "197"),
                run("ZREVRANGE", "z", "0", "2", "WITHSCORES"));
        assertEquals(integer(10), run("ZCOUNT", "z", "50", "(60"));
        assertEquals(array("m197", "m198", "m199"), run("ZRANGE", "z", "199", "201"));

        assertEquals(integer(2), run("ZREM", "z", "m100", "a100"));
        assertEquals(integer(100), run("ZRANK", "z", "z100"));
        assertEquals(bulk("100"), run("ZSCORE", "z", "z100"));
        assertEquals(bulk("7.5"), run("ZINCRBY", "z", "0.5", "m7"));
        assertEquals(array("m7", "7.5", "m8", "8"), run("ZRANGE", "z", "7", "8", "WITHSCORES"));
    }

    @Test
    @DisplayName("-0 점수 - listpack 은 저장 때 정수 0(double2ll)이라 \"0\", skiplist 는 double 그대로라 \"-0\"")
    void negativeZeroScoreDependsOnEncoding() {
        assertEquals(integer(1), run("ZADD", "small", "-0", "m"));
        assertEquals(bulk("listpack"), run("OBJECT", "ENCODING", "small"));
        assertEquals(bulk("0"), run("ZSCORE", "small", "m"));

        String longMember = "m".repeat(70);
        assertEquals(integer(1), run("ZADD", "big", "-0", longMember));
        assertEquals(bulk("skiplist"), run("OBJECT", "ENCODING", "big"));
        assertEquals(bulk("-0"), run("ZSCORE", "big", longMember));
        // Grisu2 의 최단 아닌 자릿수도 응답에 그대로
        assertEquals(bulk("99999999999999990000000"), run("ZINCRBY", "big", "1e23", longMember));
    }

    @Test
    @DisplayName("hashtable Hash - HDEL 로 줄면 dict 축소, 남은 필드 그대로, 인코딩은 hashtable 유지")
    void hashShrinksAfterHdel() {
        List<String> hset = new ArrayList<>(List.of("HSET", "h"));
        for (int i = 0; i < 600; i++) {
            hset.add("f" + i);
            hset.add("v" + i);
        }
        assertEquals(integer(600), run(hset.toArray(String[]::new)));
        HashValue hash = (HashValue) tester.keyspace.peek(Key.of("h")).value();
        assertEquals(1024, hash.dict().slots());

        for (int i = 0; i < 590; i++) {
            assertEquals(integer(1), run("HDEL", "h", "f" + i));
        }
        assertEquals(integer(10), run("HLEN", "h"));
        assertEquals(bulk("v595"), run("HGET", "h", "f595"));
        assertEquals(bulk("hashtable"), run("OBJECT", "ENCODING", "h"));
        assertTrue(shrinkTarget(hash.dict()) < 1024, "축소 안 됨: " + hash.dict().slots());
    }

    @Test
    @DisplayName("hashtable Set - SREM 으로 줄면 dict 축소, 남은 원소 그대로")
    void setShrinksAfterSrem() {
        List<String> sadd = new ArrayList<>(List.of("SADD", "s"));
        for (int i = 0; i < 600; i++) {
            sadd.add("e" + i);
        }
        assertEquals(integer(600), run(sadd.toArray(String[]::new)));
        SetValue set = (SetValue) tester.keyspace.peek(Key.of("s")).value();
        assertEquals(1024, set.dict().slots());

        for (int i = 0; i < 590; i++) {
            assertEquals(integer(1), run("SREM", "s", "e" + i));
        }
        assertEquals(integer(10), run("SCARD", "s"));
        assertEquals(integer(1), run("SISMEMBER", "s", "e599"));
        assertEquals(integer(0), run("SISMEMBER", "s", "e0"));
        assertTrue(shrinkTarget(set.dict()) < 1024, "축소 안 됨: " + set.dict().slots());
    }

    /** 축소가 시작됐으면 옮겨 갈 새 테이블, 끝났으면 현재 테이블 크기 */
    private static long shrinkTarget(Dict<?> dict) {
        return dict.isRehashing() ? dict.tableSize(1) : dict.tableSize(0);
    }

    private RespValue run(String... argv) {
        return tester.run(argv);
    }
}
