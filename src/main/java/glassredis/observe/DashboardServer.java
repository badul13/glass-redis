package glassredis.observe;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 대시보드 HTTP 서버 - JDK HttpServer로 정적 파일 + SSE 스트림
 * 탭당 EventHub 구독 1개
 * 키 목록도 같은 스트림에 keyspace 이벤트로 전송 - 시점 일치 목적
 */
public final class DashboardServer implements AutoCloseable {

    public static final int DEFAULT_PORT = 8080;

    /** 이벤트 묶음 전송 주기 */
    private static final Duration TICK = Duration.ofMillis(100);

    private static final Duration SORTED_SET_PERIOD = Duration.ofMillis(200);

    private static final long SNAPSHOT_PERIOD_MILLIS = 500;

    /** 1회 전송 이벤트 수 상한 - 나머지는 다음 차례 */
    private static final int MAX_BATCH = 500;

    /** 빌드 시 dashboard/dist 복사 위치(클래스패스) */
    private static final String STATIC_ROOT = "dashboard/";

    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "js", "text/javascript; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "json", "application/json; charset=utf-8",
            "svg", "image/svg+xml",
            "ico", "image/x-icon",
            "png", "image/png",
            "woff2", "font/woff2");

    private final String bindAddress;
    private final int requestedPort;
    private final EventHub hub;

    /** 실행 스레드 전달은 서버 쪽 담당 */
    private final Supplier<KeyspaceSnapshot> keyspace;

    private final Function<byte[], SortedSetSnapshot> sortedSet;

    private final String staticRoot;

    private HttpServer http;
    private ExecutorService handlers;
    private volatile boolean running;

    public DashboardServer(String bindAddress, int port, EventHub hub, Supplier<KeyspaceSnapshot> keyspace,
                           Function<byte[], SortedSetSnapshot> sortedSet) {
        this(bindAddress, port, hub, keyspace, sortedSet, STATIC_ROOT);
    }

    /** 테스트용 - 대시보드 미빌드 상태 재현 */
    DashboardServer(String bindAddress, int port, EventHub hub, Supplier<KeyspaceSnapshot> keyspace,
                    Function<byte[], SortedSetSnapshot> sortedSet,
                    String staticRoot) {
        this.bindAddress = bindAddress;
        this.requestedPort = port;
        this.hub = hub;
        this.keyspace = keyspace;
        this.sortedSet = sortedSet;
        this.staticRoot = staticRoot;
    }

    public void start() throws IOException {
        http = HttpServer.create(new InetSocketAddress(bindAddress, requestedPort), 0);
        // 가상 스레드 - 스트림마다 스레드 장기 점유
        handlers = Executors.newVirtualThreadPerTaskExecutor();
        http.setExecutor(handlers);
        http.createContext("/api/stream", this::handleStream);
        http.createContext("/api/zset", this::handleSortedSet);
        http.createContext("/", this::handleStatic);
        running = true;
        http.start();
    }

    public int port() {
        return http.getAddress().getPort();
    }

    @Override
    public void close() {
        running = false;
        if (http != null) {
            // 스트림 스레드가 다음 TICK에 스스로 빠져나올 유예 1초
            http.stop(1);
        }
        if (handlers != null) {
            handlers.shutdownNow();
        }
    }

    private void handleStream(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().add("Cache-Control", "no-cache");
        // 0 - 청크 전송
        exchange.sendResponseHeaders(200, 0);

        EventBuffer screen = hub.subscribe();
        try (OutputStream body = exchange.getResponseBody()) {
            stream(body, screen);
        } catch (IOException closed) {
            // 탭 닫기·새로고침 - 정상 종료
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            hub.unsubscribe(screen);
        }
    }

    private void stream(OutputStream body, EventBuffer screen) throws IOException, InterruptedException {
        // 0 - 첫 바퀴에서 키 목록 즉시 전송
        long nextSnapshot = 0;

        while (running) {
            EventRecord first = screen.poll(TICK);

            List<EventRecord> batch = new ArrayList<>();
            if (first != null) {
                batch.add(first);
                batch.addAll(screen.drain(MAX_BATCH - 1));
            }
            long dropped = screen.takeDroppedCount();
            if (!batch.isEmpty() || dropped > 0) {
                send(body, "activity", DashboardJson.activity(batch, dropped));
            }

            long now = System.currentTimeMillis();
            if (now >= nextSnapshot) {
                nextSnapshot = now + SNAPSHOT_PERIOD_MILLIS;
                KeyspaceSnapshot snapshot = keyspace.get();
                if (snapshot != null) {
                    send(body, "keyspace", DashboardJson.snapshot(snapshot));
                }
            }
            body.flush();
        }
    }

    /**
     * /api/zset?key=이름 - 화면에서 고른 키 하나의 스킵 리스트 스트림
     * 주기마다 스냅샷, 변경 시에만 전송
     */
    private void handleSortedSet(HttpExchange exchange) throws IOException {
        byte[] key = queryParameter(exchange, "key");
        if (key == null) {
            exchange.sendResponseHeaders(400, -1);
            exchange.close();
            return;
        }
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().add("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);

        try (OutputStream body = exchange.getResponseBody()) {
            String last = null;
            while (running) {
                SortedSetSnapshot snapshot = sortedSet.apply(key);
                if (snapshot != null) {
                    String json = DashboardJson.sortedSet(snapshot);
                    if (!json.equals(last)) {
                        send(body, "zset", json);
                        body.flush();
                        last = json;
                    }
                }
                Thread.sleep(SORTED_SET_PERIOD);
            }
        } catch (IOException closed) {
            // 다른 키 선택 또는 탭 닫기
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 없으면 null */
    private static byte[] queryParameter(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0 && pair.substring(0, equals).equals(name)) {
                return URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8)
                        .getBytes(StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /**
     * 끝의 빈 줄 = 한 건의 끝 - 누락 시 브라우저 무한 대기
     * JSON에 줄바꿈 없음 - data: 한 줄
     */
    private static void send(OutputStream body, String type, String json) throws IOException {
        body.write(("event: " + type + "\ndata: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
    }

    private void handleStatic(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.endsWith("/")) {
            path += "index.html";
        }
        // ".." 경로 차단 - 클래스패스 밖 접근 방지
        String resource = staticRoot + path.substring(1);
        byte[] content = path.contains("..") ? null : read(resource);

        if (content == null) {
            byte[] message = notBuiltMessage(path);
            exchange.getResponseHeaders().add("Content-Type", CONTENT_TYPES.get("html"));
            exchange.sendResponseHeaders(path.endsWith("index.html") ? 503 : 404, message.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(message);
            }
            return;
        }

        exchange.getResponseHeaders().add("Content-Type", contentType(path));
        exchange.sendResponseHeaders(200, content.length);
        try (OutputStream body = exchange.getResponseBody()) {
            body.write(content);
        }
    }

    private static byte[] read(String resource) throws IOException {
        try (InputStream in = DashboardServer.class.getClassLoader().getResourceAsStream(resource)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    private static byte[] notBuiltMessage(String path) {
        String html = """
                <!doctype html><html lang="ko"><meta charset="utf-8">
                <title>glass-redis</title>
                <body style="font-family: sans-serif; max-width: 40rem; margin: 4rem auto; line-height: 1.7">
                <h1>대시보드가 빌드되어 있지 않습니다</h1>
                <p><code>%s</code> 를 찾지 못했습니다. 대시보드는 Node 가 있으면 Gradle 빌드가 알아서 만듭니다.
                Node 를 설치한 뒤 다시 빌드해 주세요.</p>
                <pre>node --version
                ./gradlew build</pre>
                <p>화면을 고치는 중이라면 <code>cd dashboard</code> 에서 <code>npm run dev</code> 쪽이 빠릅니다.</p>
                </body></html>
                """.formatted(path);
        return html.getBytes(StandardCharsets.UTF_8);
    }

    private static String contentType(String path) {
        int dot = path.lastIndexOf('.');
        String extension = dot < 0 ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
        return CONTENT_TYPES.getOrDefault(extension, "application/octet-stream");
    }
}
