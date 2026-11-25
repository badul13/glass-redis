package glassredis.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 명령 표 고정 - arity 와 write 플래그
 * 기대값 출처 - Redis 7.2.4 src/commands/*.json 의 "arity", command_flags 의 "WRITE"
 */
class CommandTableTest {

    /** 이름 → {arity, write 여부(1/0)} */
    private static final Map<String, int[]> REDIS = new TreeMap<>();

    static {
        expect("APPEND", 3, 1);
        expect("BGREWRITEAOF", 1, 0);
        expect("BGSAVE", -1, 0);
        expect("DBSIZE", 1, 0);
        expect("DECR", 2, 1);
        expect("DECRBY", 3, 1);
        expect("DEL", -2, 1);
        expect("ECHO", 2, 0);
        expect("EXISTS", -2, 0);
        expect("EXPIRE", -3, 1);
        expect("EXPIREAT", -3, 1);
        expect("GET", 2, 0);
        expect("HDEL", -3, 1);
        expect("HEXISTS", 3, 0);
        expect("HGET", 3, 0);
        expect("HGETALL", 2, 0);
        expect("HINCRBY", 4, 1);
        expect("HKEYS", 2, 0);
        expect("HLEN", 2, 0);
        expect("HMGET", -3, 0);
        expect("HMSET", -4, 1);
        expect("HSET", -4, 1);
        expect("HVALS", 2, 0);
        expect("INCR", 2, 1);
        expect("INCRBY", 3, 1);
        expect("KEYS", 2, 0);
        expect("LASTSAVE", 1, 0);
        expect("LINDEX", 3, 0);
        expect("LLEN", 2, 0);
        expect("LPOP", -2, 1);
        expect("LPUSH", -3, 1);
        expect("LRANGE", 4, 0);
        expect("LREM", 4, 1);
        expect("LTRIM", 4, 1);
        expect("MGET", -2, 0);
        expect("MSET", -3, 1);
        expect("OBJECT", -2, 0);
        expect("PERSIST", 2, 1);
        expect("PEXPIRE", -3, 1);
        expect("PEXPIREAT", -3, 1);
        expect("PING", -1, 0);
        expect("PSYNC", -3, 0);
        expect("PTTL", 2, 0);
        expect("QUIT", -1, 0);
        expect("REPLCONF", -1, 0);
        expect("REPLICAOF", 3, 0);
        expect("ROLE", 1, 0);
        expect("RPOP", -2, 1);
        expect("RPUSH", -3, 1);
        expect("SADD", -3, 1);
        expect("SAVE", 1, 0);
        expect("SCARD", 2, 0);
        expect("SDIFF", -2, 0);
        expect("SELECT", 2, 0);
        expect("SET", -3, 1);
        expect("SINTER", -2, 0);
        expect("SISMEMBER", 3, 0);
        expect("SLAVEOF", 3, 0);
        expect("SMEMBERS", 2, 0);
        expect("SREM", -3, 1);
        expect("STRLEN", 2, 0);
        expect("SUNION", -2, 0);
        expect("SYNC", 1, 0);
        expect("TTL", 2, 0);
        expect("TYPE", 2, 0);
        expect("ZADD", -4, 1);
        expect("ZCARD", 2, 0);
        expect("ZCOUNT", 4, 0);
        expect("ZINCRBY", 4, 1);
        expect("ZRANGE", -4, 0);
        expect("ZRANGEBYSCORE", -4, 0);
        expect("ZRANK", -3, 0);
        expect("ZREM", -3, 1);
        expect("ZREVRANGE", -4, 0);
        expect("ZREVRANGEBYSCORE", -4, 0);
        expect("ZREVRANK", -3, 0);
        expect("ZSCORE", 3, 0);
    }

    private static void expect(String name, int arity, int write) {
        REDIS.put(name, new int[] {arity, write});
    }

    @Test
    @DisplayName("등록된 명령 77개의 arity·write 가 Redis 7.2.4 명령 표와 일치")
    void matchesRedisCommandTable() {
        CommandRegistry registry = CommandRegistry.withBuiltins();
        assertEquals(77, REDIS.size());
        assertEquals(REDIS.keySet(), new TreeSet<>(registry.names()));
        for (Map.Entry<String, int[]> entry : REDIS.entrySet()) {
            Command command = registry.find(entry.getKey());
            assertEquals(entry.getValue()[0], command.arity(), entry.getKey() + " arity");
            assertEquals(entry.getValue()[1] == 1, command.writes(), entry.getKey() + " write");
        }
    }
}
