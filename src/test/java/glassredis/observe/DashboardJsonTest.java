package glassredis.observe;

import glassredis.observe.Event.RemovalReason;
import glassredis.observe.KeyspaceSnapshot.KeyView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 필드 이름은 dashboard/src/types.ts 와 일치 필요 */
class DashboardJsonTest {

    @Test
    @DisplayName("명령 이벤트 - 순번, 시각, 종류 포함")
    void serialisesCommand() {
        String json = DashboardJson.activity(List.of(record(7, new Event.CommandExecuted(3, "SET", "k v", 1234, "+OK"))), 0);

        assertEquals("{\"dropped\":0,\"events\":[{\"seq\":7,\"at\":1700000000000,\"type\":\"command\","
                + "\"connection\":3,\"name\":\"SET\",\"args\":\"k v\",\"nanos\":1234,\"reply\":\"+OK\"}]}", json);
    }

    @Test
    @DisplayName("사라진 키 - 이유와 지연 시간 포함")
    void serialisesKeyRemoval() {
        String json = DashboardJson.activity(
                List.of(record(1, new Event.KeyRemoved("k", RemovalReason.ACTIVE_EXPIRED, 37))), 0);

        assertEquals("{\"dropped\":0,\"events\":[{\"seq\":1,\"at\":1700000000000,\"type\":\"keyRemoved\","
                + "\"key\":\"k\",\"reason\":\"ACTIVE_EXPIRED\",\"lateBy\":37}]}", json);
    }

    @Test
    @DisplayName("주기적 만료 한 번의 요약")
    void serialisesExpiryCycle() {
        Event cycle = new Event.ExpiryCycleCompleted("SLOW", 2, 40, 35, 900, false, 12.5);
        String json = DashboardJson.activity(List.of(record(1, cycle)), 0);

        assertEquals("{\"dropped\":0,\"events\":[{\"seq\":1,\"at\":1700000000000,\"type\":\"expiryCycle\","
                + "\"kind\":\"SLOW\",\"rounds\":2,\"sampled\":40,\"expired\":35,\"nanos\":900,"
                + "\"timeLimitHit\":false,\"stalePercent\":12.5}]}", json);
    }

    @Test
    @DisplayName("접속과 종료")
    void serialisesConnections() {
        assertEquals("{\"dropped\":0,\"events\":[{\"seq\":1,\"at\":1700000000000,\"type\":\"clientConnected\","
                        + "\"connection\":5,\"peer\":\"/127.0.0.1:52344\"}]}",
                DashboardJson.activity(List.of(record(1, new Event.ClientConnected(5, "/127.0.0.1:52344"))), 0));

        assertEquals("{\"dropped\":0,\"events\":[{\"seq\":2,\"at\":1700000000000,\"type\":\"clientDisconnected\","
                        + "\"connection\":5}]}",
                DashboardJson.activity(List.of(record(2, new Event.ClientDisconnected(5))), 0));
    }

    @Test
    @DisplayName("버린 개수는 이벤트가 없어도 전송 - 화면에 끊긴 자리 표시용")
    void reportsDroppedCount() {
        assertEquals("{\"dropped\":128,\"events\":[]}", DashboardJson.activity(List.of(), 128));
    }

    @Test
    @DisplayName("값 속 따옴표와 역슬래시 이스케이프 - 누락 시 스트림 전체 손상")
    void escapesDangerousCharacters() {
        String json = DashboardJson.activity(
                List.of(record(1, new Event.KeyRemoved("따\"옴\\표", RemovalReason.DELETED, 0))), 0);

        assertEquals("{\"dropped\":0,\"events\":[{\"seq\":1,\"at\":1700000000000,\"type\":\"keyRemoved\","
                + "\"key\":\"따\\\"옴\\\\표\",\"reason\":\"DELETED\",\"lateBy\":0}]}", json);
    }

    @Test
    @DisplayName("키 목록: 만료 시각이 없으면 null, 이미 지났는데 남아 있으면 음수")
    void serialisesSnapshot() {
        KeyspaceSnapshot snapshot = new KeyspaceSnapshot(3, 2, List.of(
                new KeyView("forever", "string", "embstr", 5, null),
                new KeyView("live", "list", "listpack", 3, 9_421L),
                new KeyView("stale", "string", "embstr", 8, -1_200L)));

        assertEquals("{\"total\":3,\"expiring\":2,\"keys\":["
                + "{\"key\":\"forever\",\"type\":\"string\",\"encoding\":\"embstr\",\"size\":5,\"ttl\":null},"
                + "{\"key\":\"live\",\"type\":\"list\",\"encoding\":\"listpack\",\"size\":3,\"ttl\":9421},"
                + "{\"key\":\"stale\",\"type\":\"string\",\"encoding\":\"embstr\",\"size\":8,\"ttl\":-1200}]}", DashboardJson.snapshot(snapshot));
    }

    private static EventRecord record(long sequence, Event event) {
        return new EventRecord(sequence, 1_700_000_000_000L, event);
    }
}
