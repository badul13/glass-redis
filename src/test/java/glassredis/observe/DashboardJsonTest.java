package glassredis.observe;

import glassredis.observe.Event.RemovalReason;
import glassredis.observe.KeyspaceSnapshot.KeyView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 브라우저로 나가는 JSON 의 모양을 못박아 둔다.
 *
 * <p>화면 쪽 타입 정의(dashboard/src/types.ts)가 이 모양을 그대로 옮겨 적은 것이라,
 * 여기서 필드 이름이 바뀌면 화면이 조용히 빈칸을 그리게 된다.
 */
class DashboardJsonTest {

    @Test
    @DisplayName("명령 이벤트는 순번, 시각, 종류와 함께 나간다")
    void serialisesCommand() {
        String json = DashboardJson.activity(List.of(record(7, new Event.CommandExecuted(3, "SET", "k v", 1234, "+OK"))), 0);

        assertEquals("{\"dropped\":0,\"events\":[{\"seq\":7,\"at\":1700000000000,\"type\":\"command\","
                + "\"connection\":3,\"name\":\"SET\",\"args\":\"k v\",\"nanos\":1234,\"reply\":\"+OK\"}]}", json);
    }

    @Test
    @DisplayName("사라진 키는 이유와 늦은 시간을 함께 싣는다")
    void serialisesKeyRemoval() {
        String json = DashboardJson.activity(
                List.of(record(1, new Event.KeyRemoved("k", RemovalReason.ACTIVE_EXPIRED, 37))), 0);

        assertEquals("{\"dropped\":0,\"events\":[{\"seq\":1,\"at\":1700000000000,\"type\":\"keyRemoved\","
                + "\"key\":\"k\",\"reason\":\"ACTIVE_EXPIRED\",\"lateBy\":37}]}", json);
    }

    @Test
    @DisplayName("만료 샘플링 한 주기의 요약")
    void serialisesExpiryCycle() {
        String json = DashboardJson.activity(List.of(record(1, new Event.ExpiryCycleCompleted(2, 40, 35, 900))), 0);

        assertEquals("{\"dropped\":0,\"events\":[{\"seq\":1,\"at\":1700000000000,\"type\":\"expiryCycle\","
                + "\"rounds\":2,\"sampled\":40,\"expired\":35,\"nanos\":900}]}", json);
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
    @DisplayName("버린 개수는 이벤트가 하나도 없어도 실어 보낸다 — 화면이 끊긴 자리를 표시해야 한다")
    void reportsDroppedCount() {
        assertEquals("{\"dropped\":128,\"events\":[]}", DashboardJson.activity(List.of(), 128));
    }

    @Test
    @DisplayName("값에 든 따옴표와 역슬래시를 이스케이프한다 — 안 하면 스트림 전체가 깨진다")
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
                new KeyView("forever", 5, null),
                new KeyView("live", 3, 9_421L),
                new KeyView("stale", 8, -1_200L)));

        assertEquals("{\"total\":3,\"expiring\":2,\"keys\":["
                + "{\"key\":\"forever\",\"bytes\":5,\"ttl\":null},"
                + "{\"key\":\"live\",\"bytes\":3,\"ttl\":9421},"
                + "{\"key\":\"stale\",\"bytes\":8,\"ttl\":-1200}]}", DashboardJson.snapshot(snapshot));
    }

    private static EventRecord record(long sequence, Event event) {
        return new EventRecord(sequence, 1_700_000_000_000L, event);
    }
}
