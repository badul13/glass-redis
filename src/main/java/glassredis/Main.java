package glassredis;

import glassredis.command.CommandRegistry;
import glassredis.server.RedisServer;

/**
 * 진입점. 인자를 파싱해서 서버를 띄운다.
 */
public final class Main {

    /**
     * 진짜 Redis 의 기본 포트는 6379 다. 같은 기계에 진짜 Redis 가 떠 있어도 부딪히지 않도록
     * 하나 옆인 6380 을 쓴다.
     */
    private static final int DEFAULT_PORT = 6380;

    /**
     * 기본은 루프백만 연다. 외부에 노출되지 않고 윈도우 방화벽 팝업도 뜨지 않는다.
     * 도커 컨테이너 안의 redis-cli 로 붙어볼 때만 {@code --bind 0.0.0.0} 으로 띄운다.
     */
    private static final String DEFAULT_BIND = "127.0.0.1";

    public static void main(String[] args) throws Exception {
        int port = DEFAULT_PORT;
        String bind = DEFAULT_BIND;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port", "-p" -> port = Integer.parseInt(requireValue(args, ++i, "--port"));
                case "--bind", "-b" -> bind = requireValue(args, ++i, "--bind");
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
        RedisServer server = new RedisServer(bind, port, registry);
        server.start();

        System.out.printf("[glass-redis] %s:%d 에서 대기 중입니다. 지원 명령 %d개: %s%n",
                bind, server.port(), registry.size(), registry.names());
        System.out.printf("[glass-redis] 접속: redis-cli -p %d   (또는 telnet %s %d)%n",
                server.port(), bind, server.port());

        // Ctrl+C 로 종료할 때 소켓을 정리한다.
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        server.awaitStop();
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

                  --port, -p <번호>    리슨 포트 (기본 6380)
                  --bind, -b <주소>    리슨 주소 (기본 127.0.0.1, 도커에서 붙으려면 0.0.0.0)
                  --help, -h           이 도움말
                """);
    }
}
