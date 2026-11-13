package glassredis.command;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 커넥션 하나의 서버 쪽 상태 - Redis client 구조체에서 명령이 쓰는 부분
 * 복제 필드는 실행 스레드 전용, lastInteraction 만 커넥션 스레드가 갱신
 */
public final class Client {

    /** SLAVE_CAPA_EOF - 디스크리스 RDB ($EOF:) 수신 가능 */
    public static final int CAPA_EOF = 1;
    /** SLAVE_CAPA_PSYNC2 - +CONTINUE <replid> 이해 */
    public static final int CAPA_PSYNC2 = 2;

    /** SLAVE_REQ_RDB_EXCLUDE_DATA - 키 없는 RDB */
    public static final int REQ_RDB_EXCLUDE_DATA = 1;
    /** SLAVE_REQ_RDB_EXCLUDE_FUNCTIONS */
    public static final int REQ_RDB_EXCLUDE_FUNCTIONS = 2;
    /** SLAVE_REQ_RDB_MASK */
    public static final int REQ_RDB_MASK = REQ_RDB_EXCLUDE_DATA | REQ_RDB_EXCLUDE_FUNCTIONS;

    private final long id;
    /** null - 소켓 없는 테스트·AOF 복구 */
    private final Socket socket;

    /** lastinteraction - 마지막으로 받은 시각 (초) */
    private volatile long lastInteraction;

    /** 이번 묶음에서 이미 만든 응답 있음 - clientHasPendingReplies */
    private boolean pendingReplies;

    /** slave_capa */
    private int replicaCapa;
    /** slave_req */
    private int replicaReq;
    /** slave_listening_port */
    private long listeningPort;
    /** slave_addr - REPLCONF ip-address, 없으면 null */
    private String announcedIp;
    /** CLIENT_REPL_RDBONLY */
    private boolean rdbOnly;

    /** CLIENT_SLAVE 가 되면 복제 쪽이 붙이는 상태 - 그 전에는 null */
    private Object replica;

    /** CLIENT_CLOSE_ASAP - 복제 쪽이 끊기로 함 */
    private volatile boolean closing;

    public Client(long id, Socket socket, long nowSeconds) {
        this.id = id;
        this.socket = socket;
        this.lastInteraction = nowSeconds;
    }

    /** 테스트·복구용 - 소켓 없음 */
    public static Client detached() {
        return new Client(0, null, 0);
    }

    public long id() {
        return id;
    }

    public Socket socket() {
        return socket;
    }

    /** 상대 IP - 소켓 없으면 null */
    public String peerIp() {
        if (socket == null || !(socket.getRemoteSocketAddress() instanceof InetSocketAddress address)) {
            return null;
        }
        return address.getAddress().getHostAddress();
    }

    public long lastInteraction() {
        return lastInteraction;
    }

    public void touch(long nowSeconds) {
        lastInteraction = nowSeconds;
    }

    public boolean hasPendingReplies() {
        return pendingReplies;
    }

    public void pendingReplies(boolean pending) {
        pendingReplies = pending;
    }

    public int replicaCapa() {
        return replicaCapa;
    }

    public void addReplicaCapa(int capa) {
        replicaCapa |= capa;
    }

    public int replicaReq() {
        return replicaReq;
    }

    public void replicaReq(int req) {
        replicaReq = req;
    }

    public long listeningPort() {
        return listeningPort;
    }

    public void listeningPort(long port) {
        listeningPort = port;
    }

    public String announcedIp() {
        return announcedIp;
    }

    public void announcedIp(String ip) {
        announcedIp = ip;
    }

    public boolean rdbOnly() {
        return rdbOnly;
    }

    public void rdbOnly(boolean value) {
        rdbOnly = value;
    }

    /** CLIENT_SLAVE */
    public boolean isReplica() {
        return replica != null;
    }

    public Object replica() {
        return replica;
    }

    public void replica(Object state) {
        replica = state;
    }

    public boolean closing() {
        return closing;
    }

    /** freeClientAsync - 소켓을 닫아 읽기·쓰기 스레드 모두 깨움 */
    public void close() {
        closing = true;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 이미 닫힘
            }
        }
    }
}
