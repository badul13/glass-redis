package glassredis.observe;

import java.util.List;

/**
 * 복제 한 시점 - 실행 스레드에서 생성 (INFO replication 에 해당하는 값들)
 *
 * @param role               master 또는 replica
 * @param replid             master_replid
 * @param replid2            master_replid2 - 승격 전 마스터의 ID, 없으면 0 40개
 * @param secondReplidOffset second_repl_offset - replid2 로 이어 받을 수 있는 마지막+1, 없으면 -1
 * @param offset             master_repl_offset - 지금까지 흘린(레플리카면 받은) 스트림 바이트
 * @param backlog            repl_backlog_active
 * @param backlogSize        repl-backlog-size 설정
 * @param backlogFirstByte   repl_backlog_first_byte_offset
 * @param backlogHistlen     repl_backlog_histlen
 * @param backlogBlocks      복제 버퍼 블록 수 - 백로그와 레플리카가 같이 씀
 * @param bufferMemory       복제 버퍼 메모리 (mem_total_replication_buffers)
 * @param socketTransfer     디스크리스 RDB 전송 중
 * @param replicas           붙어 있는 레플리카
 * @param master             레플리카일 때 마스터 쪽 상태, 마스터면 null
 */
public record ReplicationSnapshot(String role, String replid, String replid2, long secondReplidOffset, long offset,
                                  boolean backlog, long backlogSize, long backlogFirstByte, long backlogHistlen,
                                  int backlogBlocks, long bufferMemory, boolean socketTransfer,
                                  List<Replica> replicas, Master master,
                                  long syncFull, long syncPartialOk, long syncPartialErr, List<Entry> recent) {

    /**
     * 복제 흐름 한 단계 - 동기화 요청, FULLRESYNC, 부분 동기화, RDB 전송, 온라인, 끊김, ID 변경
     *
     * @param at     에포크 ms
     * @param kind   화면 분류 - sync, full, partial, rdb, online, dropped, backlog, id, link
     * @param peer   상대 (레플리카 이름 또는 마스터 주소), 없으면 "-"
     * @param offset 그 시점의 master_repl_offset
     */
    public record Entry(long at, String kind, String peer, String detail, long offset) {
    }

    /**
     * @param state      wait_bgsave, send_bulk, online 등 (INFO 의 state)
     * @param ackOffset  레플리카가 알려 온 처리 위치
     * @param ackAgo     마지막 ACK 뒤 초
     * @param sent       쓰기 스레드가 소켓에 쓴 위치
     * @param outputBytes 출력 버퍼 사용량 - 한도 판정 값
     * @param waitingAck 디스크리스 RDB 뒤 첫 ACK 대기 - 스트림 보류 중
     */
    public record Replica(long id, String name, String state, long ackOffset, long ackAgo, long sent,
                          long outputBytes, boolean waitingAck) {
    }

    /**
     * @param state       connect, connecting, handshake, sync, connected
     * @param reploff     마스터 클라이언트가 처리한 위치 - 연결 없으면 -1
     * @param lastIoAgo   마스터에게서 마지막으로 받은 뒤 초
     * @param cachedReplid 끊긴 마스터의 ID - PSYNC 로 이어 받기 시도, 없으면 null
     * @param downSeconds 링크가 끊겨 있던 초 - 연결돼 있으면 0
     */
    public record Master(String host, int port, String state, long reploff, long lastIoAgo, String cachedReplid,
                         long cachedReploff, long downSeconds) {
    }

    public ReplicationSnapshot {
        replicas = List.copyOf(replicas);
        recent = List.copyOf(recent);
    }
}
