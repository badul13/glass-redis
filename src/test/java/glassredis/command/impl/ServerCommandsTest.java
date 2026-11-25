package glassredis.command.impl;

import glassredis.command.ServerControl;
import glassredis.resp.RespValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static glassredis.command.impl.CommandTester.bulk;
import static glassredis.command.impl.CommandTester.error;
import static glassredis.command.impl.CommandTester.integer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 연결·서버 명령 - 기대 문구는 Redis 7.2.4 응답 */
class ServerCommandsTest {

    /** BGSAVE 로 넘어온 SCHEDULE 여부 기록 */
    private final List<Boolean> bgsaveCalls = new ArrayList<>();

    private final CommandTester tester = new CommandTester(new ServerControl() {
        @Override
        public RespValue bgrewriteaof() {
            return RespValue.OK;
        }

        @Override
        public RespValue save() {
            return RespValue.OK;
        }

        @Override
        public RespValue bgsave(boolean schedule) {
            bgsaveCalls.add(schedule);
            return new RespValue.SimpleString("Background saving started");
        }

        @Override
        public long lastSave() {
            return 1_700_000_000L;
        }
    });

    @Test
    @DisplayName("PING - 인자 없으면 PONG, 하나면 그대로, 둘 이상이면 인자 수 에러")
    void ping() {
        assertEquals(RespValue.PONG, run("PING"));
        assertEquals(bulk("hi"), run("PING", "hi"));
        assertEquals(error("ERR wrong number of arguments for 'ping' command"), run("PING", "a", "b"));
    }

    @Test
    @DisplayName("ECHO - 받은 바이트 그대로, 인자 수는 정확히 하나")
    void echo() {
        assertEquals(bulk("héllo"), run("ECHO", "héllo"));
        assertEquals(error("ERR wrong number of arguments for 'echo' command"), run("ECHO"));
        assertEquals(error("ERR wrong number of arguments for 'echo' command"), run("ECHO", "a", "b"));
    }

    @Test
    @DisplayName("QUIT - 인자 무관 OK")
    void quit() {
        assertEquals(RespValue.OK, run("QUIT"));
        assertEquals(RespValue.OK, run("QUIT", "x", "y"));
    }

    @Test
    @DisplayName("SELECT - DB 0 만, 범위·정수 오류는 Redis 문구 그대로")
    void select() {
        assertEquals(RespValue.OK, run("SELECT", "0"));
        assertEquals(error("ERR DB index is out of range"), run("SELECT", "1"));
        assertEquals(error("ERR DB index is out of range"), run("SELECT", "-1"));
        assertEquals(error("ERR value is not an integer or out of range"), run("SELECT", "abc"));
        assertEquals(error("ERR value is out of range, value must between -2147483648 and 2147483647"),
                run("SELECT", "2147483648"));
        assertEquals(error("ERR wrong number of arguments for 'select' command"), run("SELECT"));
    }

    @Test
    @DisplayName("BGSAVE - SCHEDULE 은 대소문자 무관, 다른 인자나 2개 이상은 syntax error")
    void bgsave() {
        assertEquals(new RespValue.SimpleString("Background saving started"), run("BGSAVE"));
        run("BGSAVE", "schedule");
        assertEquals(List.of(false, true), bgsaveCalls);

        assertEquals(error("ERR syntax error"), run("BGSAVE", "now"));
        assertEquals(error("ERR syntax error"), run("BGSAVE", "SCHEDULE", "SCHEDULE"));
        assertEquals(List.of(false, true), bgsaveCalls);
    }

    @Test
    @DisplayName("LASTSAVE - 마지막 저장 시각(초), 인자 있으면 인자 수 에러")
    void lastsave() {
        assertEquals(integer(1_700_000_000L), run("LASTSAVE"));
        assertEquals(error("ERR wrong number of arguments for 'lastsave' command"), run("LASTSAVE", "x"));
    }

    @Test
    @DisplayName("저장 기능 없는 서버 - SAVE·BGSAVE 는 Redis 의 shared.err 와 같은 ERR")
    void noPersistence() {
        CommandTester plain = new CommandTester();

        assertEquals(error("ERR"), plain.run("SAVE"));
        assertEquals(error("ERR"), plain.run("BGSAVE"));
        assertTrue(plain.run("BGREWRITEAOF") instanceof RespValue.Err);
    }

    private RespValue run(String... argv) {
        return tester.run(argv);
    }
}
