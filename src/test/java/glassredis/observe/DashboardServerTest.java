package glassredis.observe;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@Timeout(20)
class DashboardServerTest {

    private static final KeyspaceSnapshot SNAPSHOT = new KeyspaceSnapshot(1, 1,
            List.of(new KeyspaceSnapshot.KeyView("k", "string", "embstr", 3, 500L)));

    private static final SortedSetSnapshot SORTED_SET = new SortedSetSnapshot(
            "한글", "ok", "skiplist", 1, 0, 1, List.of(1L),
            List.of(new SortedSetSnapshot.NodeView("a", 1.5, List.of(-1L))));

    private final EventHub hub = new EventHub();

    private volatile String requestedKey;
    private DashboardServer dashboard;
    private HttpClient client;

    @BeforeEach
    void startDashboard() throws IOException {
        // 포트 0 - OS 가 빈 포트 선택
        dashboard = new DashboardServer("127.0.0.1", 0, hub, () -> SNAPSHOT, key -> {
            requestedKey = new String(key, StandardCharsets.UTF_8);
            return SORTED_SET;
        });
        dashboard.start();
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void stopDashboard() {
        dashboard.close();
        client.close();
    }

    @Test
    @DisplayName("스트림 접속 시 키 목록 먼저, 그 뒤 발생한 사건 순차 전송")
    void streamsKeyspaceThenActivity() throws Exception {
        HttpResponse<Stream<String>> response = client.send(
                HttpRequest.newBuilder(URI.create(streamUrl())).build(), HttpResponse.BodyHandlers.ofLines());

        assertEquals("text/event-stream; charset=utf-8",
                response.headers().firstValue("content-type").orElse(""));

        Iterator<String> lines = response.body().iterator();
        assertEquals("event: keyspace", nextEventLine(lines));
        assertEquals("data: {\"total\":1,\"expiring\":1,\"keys\":[{\"key\":\"k\",\"type\":\"string\",\"encoding\":\"embstr\",\"size\":3,\"ttl\":500}]}",
                lines.next());

        awaitScreenCount(1);
        hub.publish(new Event.ClientConnected(9, "/127.0.0.1:1234"));

        String activity = nextEventOfType(lines, "event: activity");
        assertTrue(activity.contains("\"type\":\"clientConnected\""), activity);
        assertTrue(activity.contains("\"connection\":9"), activity);
    }

    @Test
    @DisplayName("화면 연결이 끊기면 구독도 해제 - 아니면 서버가 이벤트를 끝없이 생성")
    void releasesSubscriptionWhenClientLeaves() throws Exception {
        HttpResponse<Stream<String>> response = client.send(
                HttpRequest.newBuilder(URI.create(streamUrl())).build(), HttpResponse.BodyHandlers.ofLines());
        awaitScreenCount(1);

        response.body().close();

        awaitScreenCount(0);
        assertEquals(false, hub.enabled(), "아무도 안 보면 서버는 이벤트를 만들지 않아야 한다");
    }

    @Test
    @DisplayName("스킵 리스트 스트림 - 쿼리의 키를 UTF-8 로 디코딩해 그 구조 전송")
    void streamsSortedSetOfRequestedKey() throws Exception {
        String url = "http://127.0.0.1:" + dashboard.port() + "/api/zset?key=%ED%95%9C%EA%B8%80";
        HttpResponse<Stream<String>> response = client.send(
                HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofLines());

        Iterator<String> lines = response.body().iterator();
        assertEquals("event: zset", nextEventLine(lines));
        assertEquals("data: {\"key\":\"한글\",\"status\":\"ok\",\"encoding\":\"skiplist\",\"length\":1,\"bytes\":0,\"level\":1,\"header\":[1],"
                + "\"nodes\":[{\"member\":\"a\",\"score\":1.5,\"spans\":[-1]}]}", lines.next());
        assertEquals("한글", requestedKey);
        response.body().close();
    }

    @Test
    @DisplayName("키 없는 스킵 리스트 요청은 400")
    void skipListNeedsKey() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + dashboard.port() + "/api/zset")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(400, response.statusCode());
    }

    @Test
    @DisplayName("대시보드 미빌드 시 빌드 방법 안내")
    void explainsHowToBuildTheDashboard() throws Exception {
        // 빈 경로 지정으로 빌드 전 상태 재현
        try (DashboardServer unbuilt = new DashboardServer(
                "127.0.0.1", 0, hub, () -> SNAPSHOT, key -> SORTED_SET, "nowhere/")) {
            unbuilt.start();
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + unbuilt.port() + "/")).build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(503, response.statusCode());
            assertTrue(response.body().contains("Node"), response.body());
        }
    }

    private String streamUrl() {
        return "http://127.0.0.1:" + dashboard.port() + "/api/stream";
    }

    private static String nextEventLine(Iterator<String> lines) {
        while (lines.hasNext()) {
            String line = lines.next();
            if (line.startsWith("event: ")) {
                return line;
            }
        }
        return fail("스트림이 끊겼다");
    }

    /** 해당 종류 이벤트의 data 줄 */
    private static String nextEventOfType(Iterator<String> lines, String type) {
        for (int i = 0; i < 100; i++) {
            if (nextEventLine(lines).equals(type)) {
                return lines.next();
            }
        }
        return fail(type + " 이 오지 않았다");
    }

    private void awaitScreenCount(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (hub.screenCount() != expected) {
            if (System.nanoTime() > deadline) {
                fail("구독 수가 " + expected + " 이 되지 않았다: " + hub.screenCount());
            }
            Thread.sleep(20);
        }
    }
}
