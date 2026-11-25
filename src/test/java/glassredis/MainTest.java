package glassredis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MainTest {

    @Test
    @DisplayName("ping 주기 0 - 기동 거부 (Redis 최솟값 1)")
    void rejectsZeroPingPeriod() {
        assertThrows(IllegalArgumentException.class,
                () -> Main.ranged("0", "--repl-ping-replica-period", 1, Integer.MAX_VALUE));
        assertEquals(1, Main.ranged("1", "--repl-ping-replica-period", 1, Integer.MAX_VALUE));
    }

    @Test
    @DisplayName("정수 아닌 값·최댓값 초과 - 기동 거부")
    void rejectsNonIntegerAndOverflow() {
        assertThrows(IllegalArgumentException.class, () -> Main.ranged("ten", "--repl-timeout", 1, Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> Main.ranged("65536", "--port", 0, 65535));
    }

    @Test
    @DisplayName("백로그 크기 0 - 기동 거부 (Redis 최솟값 1)")
    void rejectsZeroBacklog() {
        assertThrows(IllegalArgumentException.class, () -> Main.atLeast(Main.memory("0mb"), "--repl-backlog-size", 1));
        assertEquals(1024, Main.atLeast(Main.memory("1kb"), "--repl-backlog-size", 1));
    }
}
