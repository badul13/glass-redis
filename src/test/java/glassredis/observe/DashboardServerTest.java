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
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 진짜 HTTP 로 대시보드 서버와 이야기해 본다.
 *
 * <p>SSE 는 형식이 단순한 대신 한 글자만 틀려도 브라우저가 아무 말 없이 조용히 기다리기만 한다.
 * 그래서 실제로 소켓에 나가는 바이트를 확인한다.
 */
@Timeout(20)
class DashboardServerTest {

    private static final KeyspaceSnapshot SNAPSHOT = new KeyspaceSnapshot(1, 1,
            List.of(new KeyspaceSnapshot.KeyView("k", 3, 500L)));

    private final EventHub hub = new EventHub();
    private DashboardServer dashboard;
    private HttpClient client;

    @BeforeEach
    void startDashboard() throws IOException {
        // 포트 0 을 주면 OS 가 비어 있는 포트를 골라준다.
        dashboard = new DashboardServer("127.0.0.1", 0, hub, () -> SNAPSHOT);
        dashboard.start();
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void stopDashboard() {
        dashboard.close();
        client.close();
    }

    @Test
    @DisplayName("스트림에 붙으면 키 목록부터 내려오고, 그 뒤 벌어진 일이 이어서 온다")
    void streamsKeyspaceThenActivity() throws Exception {
        HttpResponse<Stream<String>> response = client.send(
                HttpRequest.newBuilder(URI.create(streamUrl())).build(), HttpResponse.BodyHandlers.ofLines());

        assertEquals("text/event-stream; charset=utf-8",
                response.headers().firstValue("content-type").orElse(""));

        Iterator<String> lines = response.body().iterator();
        assertEquals("event: keyspace", nextEventLine(lines));
        assertEquals("data: {\"total\":1,\"expiring\":1,\"keys\":[{\"key\":\"k\",\"bytes\":3,\"ttl\":500}]}",
                lines.next());

        awaitScreenCount(1);
        hub.publish(new Event.ClientConnected(9, "/127.0.0.1:1234"));

        String activity = nextEventOfType(lines, "event: activity");
        assertTrue(activity.contains("\"type\":\"clientConnected\""), activity);
        assertTrue(activity.contains("\"connection\":9"), activity);
    }

    @Test
    @DisplayName("보던 화면이 끊기면 구독도 같이 떨어진다 — 안 그러면 서버가 영원히 이벤트를 만든다")
    void releasesSubscriptionWhenClientLeaves() throws Exception {
        HttpResponse<Stream<String>> response = client.send(
                HttpRequest.newBuilder(URI.create(streamUrl())).build(), HttpResponse.BodyHandlers.ofLines());
        awaitScreenCount(1);

        response.body().close();

        awaitScreenCount(0);
        assertEquals(false, hub.enabled(), "아무도 안 보면 서버는 이벤트를 만들지 않아야 한다");
    }

    @Test
    @DisplayName("대시보드를 빌드하지 않았으면 빌드하는 법을 안내한다")
    void explainsHowToBuildTheDashboard() throws Exception {
        // 빌드가 끝난 환경에서는 진짜 대시보드가 클래스패스에 있다. 비어 있는 곳을 보게 해 빌드 전 상태를 만든다.
        try (DashboardServer unbuilt = new DashboardServer("127.0.0.1", 0, hub, () -> SNAPSHOT, "nowhere/")) {
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

    /** 다음 {@code event:} 줄까지 읽는다. */
    private static String nextEventLine(Iterator<String> lines) {
        while (lines.hasNext()) {
            String line = lines.next();
            if (line.startsWith("event: ")) {
                return line;
            }
        }
        return fail("스트림이 끊겼다");
    }

    /** 원하는 종류가 나올 때까지 읽고, 그 data 줄을 돌려준다. */
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
