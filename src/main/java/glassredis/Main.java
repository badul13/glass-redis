package glassredis;

import glassredis.command.CommandRegistry;
import glassredis.observe.DashboardServer;
import glassredis.observe.EventHub;
import glassredis.server.RedisServer;

import java.io.IOException;

public final class Main {

    /** 6380 - 로컬 Redis 6379와 충돌 회피 */
    private static final int DEFAULT_PORT = 6380;

    /** 루프백 - 방화벽 팝업 회피, 도커 접속 시에만 --bind 0.0.0.0 */
    private static final String DEFAULT_BIND = "127.0.0.1";

    public static void main(String[] args) throws Exception {
        int port = DEFAULT_PORT;
        String bind = DEFAULT_BIND;
        int dashboardPort = DashboardServer.DEFAULT_PORT;
        boolean dashboard = true;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port", "-p" -> port = Integer.parseInt(requireValue(args, ++i, "--port"));
                case "--bind", "-b" -> bind = requireValue(args, ++i, "--bind");
                case "--dashboard-port" ->
                        dashboardPort = Integer.parseInt(requireValue(args, ++i, "--dashboard-port"));
                case "--no-dashboard" -> dashboard = false;
                case "--help", "-h" -> {
                    printUsage();
                    return;
                }
                default -> {
                    System.err.println("알 수 없는 옵션: " + args[i]);
                    printUsage();
                    System.exit(2);
                }
            }
        }

        CommandRegistry registry = CommandRegistry.withBuiltins();

        // 구독자 없으면 이벤트 생성 생략
        EventHub events = new EventHub();

        RedisServer server = new RedisServer(bind, port, registry, events);
        server.start();

        System.out.printf("[glass-redis] %s:%d 에서 대기 중입니다. 지원 명령 %d개: %s%n",
                bind, server.port(), registry.size(), registry.names());
        System.out.printf("[glass-redis] 접속: redis-cli -p %d   (또는 telnet %s %d)%n",
                server.port(), bind, server.port());

        DashboardServer dashboardServer = dashboard ? startDashboard(bind, dashboardPort, events, server) : null;

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (dashboardServer != null) {
                dashboardServer.close();
            }
            server.close();
        }));
        server.awaitStop();
    }

    /** 대시보드 기동 실패 시 서버 유지 - 경고만 출력 */
    private static DashboardServer startDashboard(String bind, int port, EventHub events, RedisServer server) {
        DashboardServer dashboard = new DashboardServer(bind, port, events, server::keyspaceSnapshot,
                server::sortedSetSnapshot);
        try {
            dashboard.start();
            System.out.printf("[glass-redis] 대시보드: http://%s:%d%n", bind, dashboard.port());
            return dashboard;
        } catch (IOException e) {
            System.err.printf("[glass-redis] 대시보드를 %d 포트에 띄우지 못했습니다(서버는 그대로 돕니다): %s%n",
                    port, e.getMessage());
            return null;
        }
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " 에는 값이 필요합니다");
        }
        return args[index];
    }

    private static void printUsage() {
        System.out.println("""
                사용법: glass-redis [옵션]

                  --port, -p <번호>      리슨 포트 (기본 6380)
                  --bind, -b <주소>      리슨 주소 (기본 127.0.0.1, 도커에서 붙으려면 0.0.0.0)
                  --dashboard-port <번호>  대시보드 포트 (기본 8080)
                  --no-dashboard         대시보드를 띄우지 않는다
                  --help, -h             이 도움말
                """);
    }
}
