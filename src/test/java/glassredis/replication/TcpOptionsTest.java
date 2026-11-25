package glassredis.replication;

import jdk.net.ExtendedSocketOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TcpOptionsTest {

    @Test
    @DisplayName("createClient 소켓 설정 - nodelay, keepalive 300초 (간격 100초·3회, 지원하는 OS 에서)")
    void appliesNoDelayAndKeepAlive() throws IOException {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             Socket socket = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
             Socket ignored = server.accept()) {
            TcpOptions.apply(socket);

            assertTrue(socket.getTcpNoDelay());
            assertTrue(socket.getKeepAlive());
            if (socket.supportedOptions().contains(ExtendedSocketOptions.TCP_KEEPIDLE)) {
                assertEquals(300, socket.getOption(ExtendedSocketOptions.TCP_KEEPIDLE));
                assertEquals(100, socket.getOption(ExtendedSocketOptions.TCP_KEEPINTERVAL));
                assertEquals(3, socket.getOption(ExtendedSocketOptions.TCP_KEEPCOUNT));
            }
        }
    }
}
