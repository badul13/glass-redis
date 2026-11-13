package glassredis.replication;

import glassredis.command.Client;

/**
 * 마스터가 보는 레플리카 하나 - client 구조체의 CLIENT_SLAVE 부분
 * 쓰기 스레드 위치(written)를 빼면 실행 스레드 전용
 */
final class ReplicaLink implements ReplicationBuffer.Ref {

    /** replstate - SLAVE_STATE_* */
    enum State {
        /** RDB 를 만들 BGSAVE 를 기다림 */
        WAIT_BGSAVE_START,
        /** BGSAVE 진행 중 - 그동안의 명령은 버퍼에 쌓임 */
        WAIT_BGSAVE_END,
        /** 디스크 RDB 파일 전송 중 */
        SEND_BULK,
        ONLINE,
        /** rdb-only 레플리카가 RDB 를 다 받음 - 곧 끊음 */
        RDB_TRANSMITTED
    }

    final Client client;
    final ReplicaWriter writer;

    State state = State.WAIT_BGSAVE_START;
    /** CLIENT_PRE_PSYNC - SYNC 로 붙음, ACK 안 보냄 */
    boolean prePsync;
    /** psync_initial_offset - +FULLRESYNC 로 알린 오프셋 */
    long psyncInitialOffset;
    /** repl_ack_off */
    long ackOffset;
    /** repl_aof_off - REPLCONF ACK ... FACK */
    long aofOffset;
    /** repl_ack_time (초) */
    long ackTime;
    /** repl_start_cmd_stream_on_ack - 디스크리스 RDB 뒤 첫 ACK 를 받아야 스트림 시작 */
    boolean startStreamOnAck;
    /** obuf_soft_limit_reached_time (초), 0 이면 안 넘음 */
    long softLimitReachedTime;
    /** freeClient 됨 - 늦게 온 콜백 무시 */
    boolean freed;
    /** CLIENT_CLOSE_ASAP - 끊기 예약됨 */
    boolean closeAsap;

    /** 쓰기 스레드에 넘긴 스트림 위치 - 다음엔 여기 다음 바이트부터 */
    long queuedOffset;

    /** ref_repl_buf_node, ref_block_pos */
    private ReplicationBuffer.Block refBlock;
    private int refPos;

    ReplicaLink(Client client, ReplicaWriter writer) {
        this.client = client;
        this.writer = writer;
    }

    @Override
    public boolean canFeed() {
        return !client.rdbOnly() && state != State.WAIT_BGSAVE_START;
    }

    @Override
    public ReplicationBuffer.Block refBlock() {
        return refBlock;
    }

    @Override
    public int refPos() {
        return refPos;
    }

    @Override
    public void ref(ReplicationBuffer.Block block, int pos) {
        refBlock = block;
        refPos = pos;
    }

    /** putClientInPendingWriteQueue 조건 - 온라인이고 ACK 대기 아님 */
    boolean streaming() {
        return state == State.ONLINE && !startStreamOnAck;
    }

    /** 스트림 시작 위치 지정 - FULLRESYNC 오프셋 또는 PSYNC 오프셋 - 1 */
    void streamFrom(long offset) {
        queuedOffset = offset;
        writer.resetWritten(offset);
    }

    /** replicationGetSlaveName */
    String name() {
        String ip = client.announcedIp() != null ? client.announcedIp() : client.peerIp();
        if (ip == null) {
            return "client id #" + client.id();
        }
        String address = ip.contains(":") ? "[" + ip + "]" : ip;
        return client.listeningPort() != 0
                ? address + ":" + client.listeningPort()
                : address + ":<unknown-replica-port>";
    }
}
