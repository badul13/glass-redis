package glassredis.persistence;

import glassredis.command.CommandRegistry;
import glassredis.command.Context;
import glassredis.store.Keyspace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 기대 모양은 Docker redis:7 BGREWRITEAOF 결과 (aof-use-rdb-preamble no) */
class AofRewriterTest {

    private final Keyspace keyspace = new Keyspace();
    private final CommandRegistry registry = CommandRegistry.withBuiltins();
    private final Context ctx = new Context(keyspace);

    private void run(String... argv) {
        List<byte[]> all = new ArrayList<>();
        for (String arg : argv) {
            all.add(arg.getBytes(StandardCharsets.UTF_8));
        }
        ctx.propagation().begin(all);
        registry.find(argv[0].toUpperCase(Locale.ROOT)).execute(ctx, all.subList(1, all.size()));
        ctx.propagation().finish();
    }

    /** RESP 를 명령 한 줄씩 */
    private List<String> rewritten() {
        String text = new String(AofRewriter.rewrite(keyspace), StandardCharsets.UTF_8);
        List<String> commands = new ArrayList<>();
        String[] lines = text.split("\r\n");
        for (int i = 0; i < lines.length; ) {
            int argc = Integer.parseInt(lines[i].substring(1));
            List<String> args = new ArrayList<>();
            for (int j = 0; j < argc; j++) {
                args.add(lines[i + 2 + j * 2]);
            }
            commands.add(String.join(" ", args));
            i += 1 + argc * 2;
        }
        return commands;
    }

    @Test
    @DisplayName("빈 데이터는 빈 파일 - SELECT 도 없음")
    void empty() {
        assertEquals(0, AofRewriter.rewrite(keyspace).length);
    }

    @Test
    @DisplayName("INCR 여러 번은 결과 SET 한 줄 - 만료는 PEXPIREAT")
    void collapsesHistory() {
        for (int i = 0; i < 100; i++) {
            run("INCR", "counter");
        }
        run("SET", "t", "v", "PXAT", "9999999999999");
        List<String> commands = rewritten();
        assertEquals("SELECT 0", commands.getFirst());
        assertTrue(commands.contains("SET counter 100"));
        int set = commands.indexOf("SET t v");
        assertEquals("PEXPIREAT t 9999999999999", commands.get(set + 1), "값 바로 뒤에 만료");
        assertEquals(4, commands.size());
    }

    @Test
    @DisplayName("원소 64개마다 명령 하나 - 리스트 RPUSH, 셋 SADD, 해시 HMSET, 정렬 셋 ZADD")
    void chunksOf64() {
        List<String> rpush = new ArrayList<>(List.of("RPUSH", "big"));
        List<String> hset = new ArrayList<>(List.of("HSET", "hbig"));
        for (int i = 1; i <= 70; i++) {
            rpush.add(Integer.toString(i));
            hset.add("f" + i);
            hset.add("v" + i);
        }
        run(rpush.toArray(String[]::new));
        run(hset.toArray(String[]::new));
        List<String> commands = rewritten();
        assertEquals(2, commands.stream().filter(c -> c.startsWith("RPUSH big ")).count());
        assertTrue(commands.contains("RPUSH big 65 66 67 68 69 70"));
        assertTrue(commands.contains("HMSET hbig f65 v65 f66 v66 f67 v67 f68 v68 f69 v69 f70 v70"));
    }

    @Test
    @DisplayName("정렬 셋 점수는 Redis 표기 - 정수는 소수점 없이, 큰 수는 지수")
    void sortedSetScores() {
        run("ZADD", "zf", "1.5", "a", "3", "b", "1e20", "c", "-0.25", "d");
        assertTrue(rewritten().contains("ZADD zf -0.25 d 1.5 a 3 b 1e+20 c"));
    }
}
