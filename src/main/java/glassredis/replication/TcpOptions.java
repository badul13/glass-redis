package glassredis.replication;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketOption;

import jdk.net.ExtendedSocketOptions;

/**
 * createClient 의 소켓 설정 - connEnableTcpNoDelay + connKeepAlive(tcp-keepalive)
 * 일반 클라이언트·레플리카 쪽 마스터 링크 모두 같은 설정
 */
public final class TcpOptions {

    /** tcp-keepalive 기본값 (초) */
    public static final int KEEPALIVE_SECONDS = 300;

    private TcpOptions() {
    }

    /**
     * anetKeepAlive - 유휴 interval 초 뒤 첫 탐지, 이후 interval/3 간격, 3번 실패하면 끊김
     * 세부 값을 못 거는 OS 는 SO_KEEPALIVE 만 (Redis 도 리눅스 외엔 일부만)
     */
    public static void apply(Socket socket) throws IOException {
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        int interval = KEEPALIVE_SECONDS;
        setIfSupported(socket, ExtendedSocketOptions.TCP_KEEPIDLE, interval);
        setIfSupported(socket, ExtendedSocketOptions.TCP_KEEPINTERVAL, Math.max(1, interval / 3));
        setIfSupported(socket, ExtendedSocketOptions.TCP_KEEPCOUNT, 3);
    }

    private static void setIfSupported(Socket socket, SocketOption<Integer> option, int value) throws IOException {
        if (socket.supportedOptions().contains(option)) {
            socket.setOption(option, value);
        }
    }
}
