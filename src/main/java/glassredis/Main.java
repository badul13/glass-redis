package glassredis;

import glassredis.command.CommandRegistry;
import glassredis.observe.DashboardServer;
import glassredis.observe.EventHub;
import glassredis.persistence.AofLoader;
import glassredis.persistence.AppendOnlyLog;
import glassredis.persistence.RdbLoader;
import glassredis.persistence.Snapshot;
import glassredis.server.RedisServer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

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
        // AOF - Redis 기본값
        AppendOnlyLog.Config defaults = AppendOnlyLog.Config.defaults(Path.of("."));
        Path dir = defaults.dir();
        boolean appendOnly = defaults.enabled();
        AppendOnlyLog.FsyncPolicy fsync = defaults.fsync();
        int rewritePercentage = defaults.rewritePercentage();
        long rewriteMinSize = defaults.rewriteMinSize();
        boolean rdbPreamble = defaults.rdbPreamble();
        Snapshot.Config rdbDefaults = Snapshot.Config.defaults(dir);
        String dbFilename = rdbDefaults.filename();
        List<Snapshot.SaveParam> saveParams = rdbDefaults.saveParams();
        boolean stopWritesOnBgsaveError = rdbDefaults.stopWritesOnBgsaveError();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port", "-p" -> port = Integer.parseInt(requireValue(args, ++i, "--port"));
                case "--bind", "-b" -> bind = requireValue(args, ++i, "--bind");
                case "--dashboard-port" ->
                        dashboardPort = Integer.parseInt(requireValue(args, ++i, "--dashboard-port"));
                case "--no-dashboard" -> dashboard = false;
                case "--dir" -> dir = Path.of(requireValue(args, ++i, "--dir"));
                case "--appendonly" -> appendOnly = yesNo(requireValue(args, ++i, "--appendonly"), "--appendonly");
                case "--appendfsync" ->
                        fsync = AppendOnlyLog.FsyncPolicy.parse(requireValue(args, ++i, "--appendfsync"));
                case "--auto-aof-rewrite-percentage" ->
                        rewritePercentage = Integer.parseInt(requireValue(args, ++i, "--auto-aof-rewrite-percentage"));
                case "--dbfilename" -> dbFilename = requireValue(args, ++i, "--dbfilename");
                case "--save" -> saveParams = Snapshot.Config.parseSave(requireValue(args, ++i, "--save"));
                case "--stop-writes-on-bgsave-error" -> stopWritesOnBgsaveError =
                        yesNo(requireValue(args, ++i, "--stop-writes-on-bgsave-error"), "--stop-writes-on-bgsave-error");
                case "--auto-aof-rewrite-min-size" ->
                        rewriteMinSize = memory(requireValue(args, ++i, "--auto-aof-rewrite-min-size"));
                case "--aof-use-rdb-preamble" -> rdbPreamble =
                        yesNo(requireValue(args, ++i, "--aof-use-rdb-preamble"), "--aof-use-rdb-preamble");
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

        AppendOnlyLog aof = new AppendOnlyLog(
                new AppendOnlyLog.Config(dir, appendOnly, fsync, rewritePercentage, rewriteMinSize, rdbPreamble),
                events);
        Snapshot snapshot = new Snapshot(new Snapshot.Config(dir, dbFilename, saveParams, stopWritesOnBgsaveError));
        RedisServer server = new RedisServer(bind, port, registry, events, aof, snapshot);
        server.start();
        reportAof(aof);
        reportRdb(snapshot);

        System.out.printf("[glass-redis] %s:%d 에서 대기 중입니다. 지원 명령 %d개: %s%n",
                bind, server.port(), registry.size(), registry.names());
        System.out.printf("[glass-redis] 접속: redis-cli -p %d   (또는 telnet %s %d)%n",
                server.port(), bind, server.port());

        DashboardServer dashboardServer = dashboard ? startDashboard(bind, dashboardPort, events, server) : null;

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (dashboardServer != null) {
                dashboardServer.close();
            }
            server.shutdown();
        }));
        server.awaitStop();
    }

    /** 대시보드 기동 실패 시 서버 유지 - 경고만 출력 */
    private static DashboardServer startDashboard(String bind, int port, EventHub events, RedisServer server) {
        DashboardServer dashboard = new DashboardServer(bind, port, events, server::keyspaceSnapshot,
                server::sortedSetSnapshot).withAof(server::aofSnapshot);
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

    /** 기동 시 RDB 복구 결과 - AOF 로 복구했으면 생략 */
    private static void reportRdb(Snapshot snapshot) {
        RdbLoader.Result loaded = snapshot.loadResult();
        if (loaded == null) {
            return; // AOF 로 복구
        }
        if (loaded.status() == RdbLoader.Status.OK) {
            System.out.printf("[glass-redis] RDB 에서 키 %d개를 복구했습니다 (만료로 버린 키 %d개, %d바이트, %dms, redis-ver %s)%n",
                    loaded.keys(), loaded.expired(), loaded.bytes(), loaded.elapsedMillis(), loaded.createdBy());
        } else {
            System.out.println("[glass-redis] RDB 파일이 없어 빈 데이터로 시작합니다");
        }
        StringBuilder save = new StringBuilder();
        snapshot.config().saveParams().forEach(p -> save.append(save.isEmpty() ? "" : " ")
                .append(p.seconds()).append(' ').append(p.changes()));
        System.out.printf("[glass-redis] RDB: %s (save \"%s\")%n", snapshot.file().toAbsolutePath().normalize(), save);
    }

    /** 기동 시 복구 결과 - Redis 로그처럼 */
    private static void reportAof(AppendOnlyLog aof) {
        if (!aof.enabled()) {
            return;
        }
        AofLoader.Result loaded = aof.loadResult();
        switch (loaded.status()) {
            case OK, TRUNCATED -> System.out.printf(
                    "[glass-redis] AOF 에서 복구했습니다 - RDB base 키 %d개, 명령 %d개 재실행 (%d바이트, %dms)%s%n",
                    loaded.rdbKeys(), loaded.commands(), loaded.bytes(), loaded.elapsedMillis(),
                    loaded.truncatedFile() == null ? "" : " - " + loaded.truncatedFile() + " 끝부분 잘라냄");
            case EMPTY, NOT_EXIST -> System.out.println("[glass-redis] AOF 가 비어 있어 빈 데이터로 시작합니다");
            case FAILED -> {
                // start() 에서 예외 - 도달 불가
            }
        }
        System.out.printf("[glass-redis] AOF: %s (appendfsync %s)%n", aof.directory().toAbsolutePath().normalize(),
                aof.config().fsync().configName());
    }

    private static boolean yesNo(String value, String option) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "yes" -> true;
            case "no" -> false;
            default -> throw new IllegalArgumentException(option + " 에는 yes 또는 no 가 필요합니다");
        };
    }

    /** Redis 메모리 표기 - 1k=1000, 1kb=1024, m·mb, g·gb */
    static long memory(String value) {
        String text = value.toLowerCase(Locale.ROOT);
        long unit = 1;
        String[][] units = {{"gb", "1073741824"}, {"mb", "1048576"}, {"kb", "1024"}, {"g", "1000000000"},
                {"m", "1000000"}, {"k", "1000"}, {"b", "1"}};
        for (String[] u : units) {
            if (text.endsWith(u[0])) {
                unit = Long.parseLong(u[1]);
                text = text.substring(0, text.length() - u[0].length());
                break;
            }
        }
        return Math.multiplyExact(Long.parseLong(text), unit);
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
                  --appendonly yes|no    AOF 기록·복구 (기본 no)
                  --dir <폴더>           AOF 폴더(appendonlydir)와 RDB 파일을 둘 곳 (기본 현재 폴더)
                  --dbfilename <이름>    RDB 파일 이름 (기본 dump.rdb) - AOF 꺼져 있으면 기동 시 여기서 복구
                  --save "<초> <횟수> ..."  이 시간 안에 이만큼 바뀌면 BGSAVE (기본 "3600 1 300 100 60 10000", ""는 끔)
                                         규칙이 있으면 종료(Ctrl+C) 때도 저장
                  --stop-writes-on-bgsave-error yes|no  BGSAVE 실패 시 쓰기 명령 거부 (기본 yes)
                  --appendfsync <정책>   always | everysec | no (기본 everysec)
                  --auto-aof-rewrite-percentage <%>  마지막 재작성 대비 이만큼 커지면 자동 재작성 (기본 100, 0은 끔)
                  --auto-aof-rewrite-min-size <크기>  이보다 작으면 자동 재작성 안 함 (기본 64mb)
                  --aof-use-rdb-preamble yes|no  재작성 base 를 RDB 형식으로 (기본 yes, no 면 명령 나열)
                  --help, -h             이 도움말
                """);
    }
}
