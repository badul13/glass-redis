package glassredis.observe;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * 대시보드를 띄우는 HTTP 서버. Redis 포트와는 다른 포트를 쓴다.
 *
 * <p>JDK 에 들어 있는 {@link HttpServer} 를 쓴다. 의존성을 하나도 늘리지 않고 정적 파일과
 * 이벤트 스트림을 내보내는 데에는 충분하다.
 *
 * <h2>왜 SSE 인가</h2>
 * 서버가 브라우저로 밀어주기만 하면 되고 브라우저가 서버로 보낼 것은 없다. 그래서 웹소켓처럼
 * 양방향 연결을 세울 이유가 없다. SSE 는 그냥 끊기지 않는 HTTP 응답이라 프록시도 잘 통과하고,
 * 끊기면 브라우저가 알아서 다시 붙는다.
 *
 * <h2>화면마다 자기 큐</h2>
 * 접속하면 {@link EventHub} 에 구독을 하나 등록하고, 끊으면 뗀다. 탭을 두 개 열면 큐도 두 개다.
 * 마지막 탭을 닫으면 구독이 0 이 되고, 그때부터 서버는 이벤트를 만들지도 않는다.
 *
 * <h2>키 목록은 스트림에 실어 보낸다</h2>
 * 별도의 조회 API 를 두고 브라우저가 주기적으로 물어보게 할 수도 있지만, 그러면 연결이 둘이 되고
 * 이벤트와 목록의 시점이 어긋난다. 같은 스트림에 {@code keyspace} 라는 이름으로 같이 실어 보낸다.
 */
public final class DashboardServer implements AutoCloseable {

    /** 기본 포트. Redis 쪽(6380)과 겹치지 않게 흔한 개발용 포트를 쓴다. */
    public static final int DEFAULT_PORT = 8080;

    /** 이벤트를 모아 보내는 주기. 초당 10번이면 사람 눈에는 실시간이고, 브라우저는 충분히 따라온다. */
    private static final Duration TICK = Duration.ofMillis(100);

    /** 키 목록을 다시 뜨는 주기. 남은 TTL 이 줄어드는 게 보일 정도면 된다. */
    private static final long SNAPSHOT_PERIOD_MILLIS = 500;

    /** 한 번에 보내는 이벤트 수 상한. 이보다 많이 밀려 있으면 다음 차례에 마저 보낸다. */
    private static final int MAX_BATCH = 500;

    /** 정적 파일을 찾는 클래스패스 위치. {@code dashboard/dist} 를 빌드가 여기로 복사해 둔다. */
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

    /** 키 목록을 떠 오는 통로. 실행 스레드에 부탁하는 일은 서버 쪽이 알아서 한다. */
    private final Supplier<KeyspaceSnapshot> keyspace;

    private final String staticRoot;

    private HttpServer http;
    private ExecutorService handlers;
    private volatile boolean running;

    public DashboardServer(String bindAddress, int port, EventHub hub, Supplier<KeyspaceSnapshot> keyspace) {
        this(bindAddress, port, hub, keyspace, STATIC_ROOT);
    }

    /** 정적 파일을 다른 데서 찾게 한다. 대시보드를 빌드하지 않은 상태를 테스트할 때만 쓴다. */
    DashboardServer(String bindAddress, int port, EventHub hub, Supplier<KeyspaceSnapshot> keyspace,
                    String staticRoot) {
        this.bindAddress = bindAddress;
        this.requestedPort = port;
        this.hub = hub;
        this.keyspace = keyspace;
        this.staticRoot = staticRoot;
    }

    public void start() throws IOException {
        http = HttpServer.create(new InetSocketAddress(bindAddress, requestedPort), 0);
        // 스트림 하나가 스레드 하나를 붙잡고 끝나지 않는다. 가상 스레드라 붙잡혀 있어도 비싸지 않다.
        handlers = Executors.newVirtualThreadPerTaskExecutor();
        http.setExecutor(handlers);
        http.createContext("/api/stream", this::handleStream);
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
            // 스트림 스레드는 running 을 보고 다음 차례(최대 100ms)에 스스로 빠져나온다.
            // 여기서 기다려주지 않으면 stop() 이 그 연결을 강제로 끊는다.
            http.stop(1);
        }
        if (handlers != null) {
            handlers.shutdownNow();
        }
    }

    private void handleStream(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().add("Cache-Control", "no-cache");
        // 길이를 모르는 응답이다. 0 을 주면 청크 방식으로 끝없이 내보낸다.
        exchange.sendResponseHeaders(200, 0);

        EventBuffer screen = hub.subscribe();
        try (OutputStream body = exchange.getResponseBody()) {
            stream(body, screen);
        } catch (IOException closed) {
            // 브라우저가 탭을 닫거나 새로고침했다. 스트림에서는 이게 정상 종료다.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            hub.unsubscribe(screen);
        }
    }

    private void stream(OutputStream body, EventBuffer screen) throws IOException, InterruptedException {
        // 0 으로 두면 첫 바퀴에서 바로 키 목록을 보낸다. 안 그러면 첫 명령이 올 때까지 화면이 비어 있다.
        long nextSnapshot = 0;

        while (running) {
            // 여기서 최대 TICK 만큼 기다린다. 이벤트가 오면 바로 깨어나고, 없으면 시간이 차서 깨어난다.
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
     * SSE 한 덩어리를 쓴다.
     *
     * <p>형식은 {@code event: 이름}, {@code data: 내용}, 그리고 빈 줄 하나다. 빈 줄이 "여기까지가 한 건"이라는
     * 표시라서 빠뜨리면 브라우저는 계속 다음 줄을 기다린다. JSON 안에는 줄바꿈이 들어가지 않으므로
     * {@code data:} 한 줄로 끝난다.
     */
    private static void send(OutputStream body, String type, String json) throws IOException {
        body.write(("event: " + type + "\ndata: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
    }

    private void handleStatic(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.endsWith("/")) {
            path += "index.html";
        }
        // ".." 로 클래스패스 밖을 짚는 요청을 막는다.
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

    /** 대시보드를 아직 빌드하지 않았을 때 브라우저에 그대로 띄워줄 안내. */
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
