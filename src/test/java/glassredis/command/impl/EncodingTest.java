package glassredis.command.impl;

import glassredis.resp.RespValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static glassredis.command.impl.CommandTester.bulk;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 인코딩이 바뀌는 경계. 기대값은 전부 실제 Redis 7.4 에 같은 명령을 보내고 {@code OBJECT ENCODING} 으로 받아 본 것이다.
 */
class EncodingTest {

    private final CommandTester tester = new CommandTester();

    @Test
    @DisplayName("문자열: 정수 표기면 int, 44바이트까지 embstr, 넘거나 APPEND 로 고치면 raw")
    void strings() {
        assertEncoding("int", "SET", "a", "12");
        assertEncoding("int", "SET", "a", "-9223372036854775808");
        assertEncoding("embstr", "SET", "a", "012");
        assertEncoding("embstr", "SET", "a", "9223372036854775808");
        assertEncoding("embstr", "SET", "a", "b".repeat(44));
        assertEncoding("raw", "SET", "a", "b".repeat(45));

        run("SET", "n", "5");
        run("APPEND", "n", "1");
        assertEquals(bulk("raw"), encoding("n"));
        run("INCR", "n");
        assertEquals(bulk("int"), encoding("n"));
    }

    @Test
    @DisplayName("List: 1바이트 원소 2729개까지 listpack, 2730번째에 quicklist, 1363개로 줄면 되돌아온다")
    void list() {
        for (int i = 0; i < 2729; i++) {
            run("RPUSH", "l", "x");
        }
        assertEquals(bulk("listpack"), encoding("l"));
        run("RPUSH", "l", "x");
        assertEquals(bulk("quicklist"), encoding("l"));

        for (int i = 2730; i > 1364; i--) {
            run("RPOP", "l");
        }
        assertEquals(bulk("quicklist"), encoding("l"));
        run("RPOP", "l");
        assertEquals(bulk("listpack"), encoding("l"));
    }

    @Test
    @DisplayName("Hash: 512개·64바이트까지 listpack, 넘으면 hashtable, 줄어도 안 돌아온다")
    void hash() {
        for (int i = 0; i < 512; i++) {
            run("HSET", "h", "f" + i, "v");
        }
        assertEquals(bulk("listpack"), encoding("h"));
        run("HSET", "h", "extra", "v");
        assertEquals(bulk("hashtable"), encoding("h"));
        run("HDEL", "h", "extra", "f1");
        assertEquals(bulk("hashtable"), encoding("h"));

        run("HSET", "v", "f", "x".repeat(64));
        assertEquals(bulk("listpack"), encoding("v"));
        run("HSET", "v", "g", "x".repeat(65));
        assertEquals(bulk("hashtable"), encoding("v"));
    }

    @Test
    @DisplayName("Set: 정수만이면 intset, 작으면 listpack, 정수 200개에 문자열이 오면 곧장 hashtable")
    void set() {
        run("SADD", "i", "3", "1", "2");
        assertEquals(bulk("intset"), encoding("i"));
        assertEquals(new RespValue.Array(List.of(bulk("1"), bulk("2"), bulk("3"))), run("SMEMBERS", "i"));
        run("SADD", "i", "a");
        assertEquals(bulk("listpack"), encoding("i"));

        run("SADD", "z", "007");
        assertEquals(bulk("listpack"), encoding("z"));

        for (int i = 0; i < 200; i++) {
            run("SADD", "big", Integer.toString(i));
        }
        run("SADD", "big", "x");
        assertEquals(bulk("hashtable"), encoding("big"));

        for (int i = 0; i <= 512; i++) {
            run("SADD", "ints", Integer.toString(i));
        }
        assertEquals(bulk("hashtable"), encoding("ints"));
    }

    @Test
    @DisplayName("Sorted Set: 128개·64바이트까지 listpack, 한 번에 129개면 처음부터 skiplist")
    void sortedSet() {
        for (int i = 0; i < 128; i++) {
            run("ZADD", "z", Integer.toString(i), "m" + i);
        }
        assertEquals(bulk("listpack"), encoding("z"));
        run("ZADD", "z", "999", "extra");
        assertEquals(bulk("skiplist"), encoding("z"));
        run("ZREM", "z", "extra", "m1");
        assertEquals(bulk("skiplist"), encoding("z"));

        List<String> bulkAdd = new ArrayList<>(List.of("ZADD", "b"));
        for (int i = 0; i < 129; i++) {
            bulkAdd.add(Integer.toString(i));
            bulkAdd.add("n" + i);
        }
        run(bulkAdd.toArray(String[]::new));
        assertEquals(bulk("skiplist"), encoding("b"));

        run("ZADD", "long", "1", "m".repeat(65));
        assertEquals(bulk("skiplist"), encoding("long"));
    }

    @Test
    @DisplayName("OBJECT 의 에러와 없는 키")
    void objectErrors() {
        assertEquals(RespValue.NIL, encoding("missing"));
        assertEquals(new RespValue.Err("ERR unknown subcommand 'FOO'. Try OBJECT HELP."), run("OBJECT", "FOO"));
        assertEquals(new RespValue.Err("ERR wrong number of arguments for 'object|encoding' command"),
                run("OBJECT", "ENCODING"));
    }

    private void assertEncoding(String expected, String... command) {
        run(command);
        assertEquals(bulk(expected), encoding(command[1]), String.join(" ", command));
    }

    private RespValue encoding(String key) {
        return run("OBJECT", "ENCODING", key);
    }

    private RespValue run(String... argv) {
        return tester.run(argv);
    }
}
