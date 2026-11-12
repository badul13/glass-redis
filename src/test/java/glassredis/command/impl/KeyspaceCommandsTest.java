package glassredis.command.impl;

import glassredis.resp.RespValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static glassredis.command.impl.CommandTester.integer;
import static org.junit.jupiter.api.Assertions.assertEquals;

class KeyspaceCommandsTest {

    private final CommandTester tester = new CommandTester();

    @Test
    @DisplayName("KEYS - 패턴에 맞는 키만, 순서는 dict 순회 순")
    void keysByPattern() {
        tester.run("SET", "user:1", "a");
        tester.run("SET", "user:2", "b");
        tester.run("SET", "order:1", "c");

        assertEquals(List.of("order:1", "user:1", "user:2"), keys("*"));
        assertEquals(List.of("user:1", "user:2"), keys("user:*"));
        assertEquals(List.of("order:1", "user:1"), keys("*:1"));
        assertEquals(List.of(), keys("none*"));
    }

    @Test
    @DisplayName("빈 이름 키 - KEYS * 지름길로만 포함, KEYS ** 는 매칭 함수 거쳐 제외")
    void emptyKeyName() {
        tester.run("SET", "", "v");

        assertEquals(List.of(""), keys("*"));
        assertEquals(List.of(), keys("**"));
    }

    @Test
    @DisplayName("만료 키 - KEYS 에선 빠지되 삭제는 안 됨, DBSIZE 는 그대로 셈")
    void expiredKeysHiddenButNotDeleted() {
        tester.run("SET", "live", "v");
        tester.run("SET", "dying", "v", "PX", "100");
        tester.clock.advanceMillis(101);

        assertEquals(List.of("live"), keys("*"));
        assertEquals(integer(2), tester.run("DBSIZE"), "KEYS 가 지우지 않았으므로 아직 2");
        assertEquals(List.of(), tester.propagated.stream().filter(c -> c.startsWith("DEL")).toList());

        tester.run("GET", "dying");
        assertEquals(integer(1), tester.run("DBSIZE"), "GET 의 지연 만료로 삭제");
    }

    private List<String> keys(String pattern) {
        RespValue.Array reply = (RespValue.Array) tester.run("KEYS", pattern);
        List<String> names = new ArrayList<>();
        for (RespValue item : reply.items()) {
            names.add(new String(((RespValue.BulkString) item).bytes(), StandardCharsets.UTF_8));
        }
        names.sort(null);
        return names;
    }
}
