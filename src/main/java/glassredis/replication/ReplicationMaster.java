package glassredis.replication;

import glassredis.command.Client;
import glassredis.command.Errors;
import glassredis.command.Numbers;
import glassredis.command.Replication;
import glassredis.persistence.RdbWriter;
import glassredis.resp.RespReader;
import glassredis.resp.RespValue;
import glassredis.store.Keyspace;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * 마스터 쪽 복제 - replication.c 의 MASTER 부분
 * 실행 스레드 전용 - 소켓 쓰기만 레플리카별 쓰기 스레드
 *
 * <pre>
 *   쓰기 명령 ─▶ feed() ─▶ 공유 버퍼 블록 ─▶ beforeSleep 에 레플리카별로 넘김 ─▶ 쓰기 스레드 ─▶ 소켓
 *                              │
 *                              └─ 백로그 (repl-backlog-size) - 끊겼다 돌아온 레플리카의 PSYNC 용
 * </pre>
 */
public final class ReplicationMaster implements Replication {

    /** CONFIG_RUN_ID_SIZE */
    static final int ID_SIZE = 40;
    /** RDB_EOF_MARK_SIZE */
    static final int EOF_MARK_SIZE = 40;
    /** NET_HOST_STR_LEN */
    private static final int HOST_STR_LEN = 256;

    private static final byte[] NEWLINE = {'\n'};
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 복제 설정 - redis.conf 이름 그대로
     *
     * @param disklessSync            repl-diskless-sync - RDB 를 파일 없이 소켓으로
     * @param disklessSyncDelay       repl-diskless-sync-delay - 다른 레플리카를 기다리는 초
     * @param disklessSyncMaxReplicas repl-diskless-sync-max-replicas - 이만큼 모이면 지연 없이 시작, 0 은 끔
     * @param backlogSize             repl-backlog-size
     * @param backlogTtl              repl-backlog-ttl - 레플리카 없이 이만큼 지나면 백로그 해제, 0 은 안 함
     * @param pingPeriod              repl-ping-replica-period - 스트림에 PING 넣는 주기 (초)
     * @param timeout                 repl-timeout
     * @param outputHardLimit         client-output-buffer-limit replica 의 hard
     * @param outputSoftLimit         같은 설정의 soft
     * @param outputSoftSeconds       soft 를 넘긴 채 버티는 초
     */
    public record Config(boolean disklessSync, long disklessSyncDelay, long disklessSyncMaxReplicas,
                         long backlogSize, long backlogTtl, long pingPeriod, long timeout,
                         long outputHardLimit, long outputSoftLimit, long outputSoftSeconds) {

        /** CONFIG_REPL_BACKLOG_MIN_SIZE */
        public static final long MIN_BACKLOG_SIZE = 16 * 1024;

        public Config {
            backlogSize = Math.max(backlogSize, MIN_BACKLOG_SIZE);
        }

        public static Config defaults() {
            return new Config(true, 5, 0, 1024 * 1024, 3600, 10, 60,
                    256L * 1024 * 1024, 64L * 1024 * 1024, 60);
        }
    }

    /** 다른 백그라운드 작업 - AOF 재작성, 디스크 BGSAVE (Persistence 가 구현) */
    public interface Children {
        /** AOF 재작성 또는 디스크 BGSAVE 진행 중 */
        boolean active();

        /** child_type == RDB, rdb_child_type == DISK */
        boolean diskBgsaveActive();

        /** rdbSaveBackground - 복제용 디스크 BGSAVE 시작, 실패면 false */
        boolean startDiskBgsave();

        /** server.rdb_filename */
        Path rdbFile();

        /** server.saveparamslen == 0 - save 규칙 없음 */
        boolean saveRulesEmpty();

        /** killRDBChild - 디스크 BGSAVE 중단 */
        void killDiskBgsave();
    }

    private final Config config;
    private final Keyspace keyspace;
    private final Children children;
    /** 실행 스레드 큐 - 쓰기 스레드 콜백을 실행 스레드로 */
    private final Consumer<Runnable> executor;
    /** server.unixtime */
    private final LongSupplier clock;

    /** server.slaves */
    private final List<ReplicaLink> slaves = new ArrayList<>();
    private final ReplicationBuffer buffer;

    /** server.replid */
    private String replid;
    /** server.replid2 */
    private String replid2;
    /** server.second_replid_offset */
    private long secondReplidOffset;
    /** server.slaveseldb - -1 이면 다음 명령 앞에 SELECT */
    private int slaveseldb = -1;
    /** server.repl_no_slaves_since */
    private long noSlavesSince;
    /** replication_cron_loops */
    private long cronLoops;

    /** 디스크리스 전송 - child_type RDB + rdb_child_type SOCKET, 없으면 null */
    private CompletableFuture<Void> socketTransfer;

    private long statSyncFull;
    private long statSyncPartialOk;
    private long statSyncPartialErr;

    /** 디스크리스 RDB 의 EOF·CRC64 계산 - fork 된 자식 역할 */
    private final ExecutorService rdbThread = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("glass-redis-repl-rdb").daemon(true).factory());

    public ReplicationMaster(Config config, Keyspace keyspace, Children children, Consumer<Runnable> executor,
                             LongSupplier clock) {
        this.config = config;
        this.keyspace = keyspace;
        this.children = children;
        this.executor = executor;
        this.clock = clock;
        this.buffer = new ReplicationBuffer(config.backlogSize(), slaves, this::checkOutputBufferLimit);
        // initServer - 기동마다 새 ID
        changeReplicationId();
        clearReplicationId2();
        this.noSlavesSince = clock.getAsLong();
    }

    public Config config() {
        return config;
    }

    public String replid() {
        return replid;
    }

    public long masterReplOffset() {
        return buffer.masterReplOffset();
    }

    /** shouldPropagate 의 복제 쪽 - 백로그나 레플리카가 있음 */
    public boolean propagating() {
        return buffer.hasBacklog() || !slaves.isEmpty();
    }

    /** 디스크리스 전송 중 - hasActiveChildProcess 에 포함 */
    public boolean socketChildActive() {
        return socketTransfer != null;
    }

    /**
     * replicationFeedSlaves - 명령 하나를 RESP 배열로 스트림에 추가
     *
     * @param dbid -1 이면 SELECT 안 붙임 (MULTI, EXEC, PING)
     */
    public void feed(int dbid, List<byte[]> argv) {
        if (!buffer.hasBacklog() && slaves.isEmpty()) {
            // AOF 만 켜져 있을 때도 명령마다 1 증가 (WAITAOF 용 오프셋)
            buffer.advanceWithoutBacklog();
            return;
        }
        if (dbid != -1 && slaveseldb != dbid) {
            buffer.feed(command(List.of(ascii("SELECT"), ascii(Integer.toString(dbid)))));
            slaveseldb = dbid;
        }
        buffer.feed(command(argv));
    }

    /** rdbPopulateSaveInfo - 백로그 있는 마스터만, 아니면 null */
    public RdbWriter.ReplInfo saveInfo() {
        if (!buffer.hasBacklog()) {
            return null;
        }
        return new RdbWriter.ReplInfo(slaveseldb == -1 ? 0 : slaveseldb, replid, buffer.masterReplOffset());
    }

    // ---------------------------------------------------------------- 명령

    @Override
    public RespValue replconf(Client client, List<byte[]> args) {
        // 옵션-값 쌍 - 이름 포함 인자 수가 홀수
        if ((args.size() + 1) % 2 == 0) {
            return Errors.syntaxError();
        }
        for (int i = 0; i < args.size(); i += 2) {
            String option = text(args.get(i));
            byte[] value = args.get(i + 1);
            if (option.equalsIgnoreCase("listening-port")) {
                OptionalLong port = Numbers.parseLong(value);
                if (port.isEmpty()) {
                    return Errors.notAnInteger();
                }
                client.listeningPort(port.getAsLong());
            } else if (option.equalsIgnoreCase("ip-address")) {
                if (value.length >= HOST_STR_LEN) {
                    return new RespValue.Err("ERR REPLCONF ip-address provided by replica instance is too long: "
                            + value.length + " bytes");
                }
                client.announcedIp(text(value));
            } else if (option.equalsIgnoreCase("capa")) {
                // 모르는 능력은 무시
                String capa = text(value);
                if (capa.equalsIgnoreCase("eof")) {
                    client.addReplicaCapa(Client.CAPA_EOF);
                } else if (capa.equalsIgnoreCase("psync2")) {
                    client.addReplicaCapa(Client.CAPA_PSYNC2);
                }
            } else if (option.equalsIgnoreCase("ack")) {
                return ack(client, args, i);
            } else if (option.equalsIgnoreCase("getack")) {
                // 레플리카일 때만 의미 - 마스터는 아무것도 안 함
                return RespValue.NO_REPLY;
            } else if (option.equalsIgnoreCase("rdb-only")) {
                OptionalLong flag = Numbers.parseLong(value);
                if (flag.isEmpty()) {
                    return Errors.notAnInteger();
                }
                if (flag.getAsLong() < 0 || flag.getAsLong() > 1) {
                    return new RespValue.Err("ERR value is out of range, value must between 0 and 1");
                }
                client.rdbOnly(flag.getAsLong() == 1);
            } else if (option.equalsIgnoreCase("rdb-filter-only")) {
                List<byte[]> filters = RespReader.splitArgs(value, value.length);
                if (filters == null) {
                    return new RespValue.Err("ERR Missing rdb-filter-only values");
                }
                // 기본은 전부 빼고, 적힌 것만 넣음
                int req = client.replicaReq() | Client.REQ_RDB_EXCLUDE_DATA | Client.REQ_RDB_EXCLUDE_FUNCTIONS;
                for (byte[] filter : filters) {
                    if (text(filter).equalsIgnoreCase("functions")) {
                        req &= ~Client.REQ_RDB_EXCLUDE_FUNCTIONS;
                    } else {
                        client.replicaReq(req);
                        return new RespValue.Err("ERR Unsupported rdb-filter-only option: " + text(filter));
                    }
                }
                client.replicaReq(req);
            } else {
                return new RespValue.Err("ERR Unrecognized REPLCONF option: " + option);
            }
        }
        return RespValue.OK;
    }

    /** REPLCONF ACK <offset> [FACK <aofofs>] - 응답 없음 */
    private RespValue ack(Client client, List<byte[]> args, int index) {
        if (!(client.replica() instanceof ReplicaLink slave)) {
            return RespValue.NO_REPLY;
        }
        OptionalLong offset = Numbers.parseLong(args.get(index + 1));
        if (offset.isEmpty()) {
            return RespValue.NO_REPLY;
        }
        if (offset.getAsLong() > slave.ackOffset) {
            slave.ackOffset = offset.getAsLong();
        }
        if (args.size() > index + 3 && text(args.get(index + 2)).equalsIgnoreCase("fack")) {
            OptionalLong aof = Numbers.parseLong(args.get(index + 3));
            if (aof.isEmpty()) {
                return RespValue.NO_REPLY;
            }
            if (aof.getAsLong() > slave.aofOffset) {
                slave.aofOffset = aof.getAsLong();
            }
        }
        slave.ackTime = clock.getAsLong();
        // 디스크리스면 ACK 가 전송 완료 감지보다 먼저 올 수 있음 - 바로 확인
        if (socketTransfer != null && slave.state == ReplicaLink.State.WAIT_BGSAVE_END) {
            checkSocketChildDone();
        }
        if (slave.startStreamOnAck && slave.state == ReplicaLink.State.ONLINE) {
            startCommandStream(slave);
        }
        return RespValue.NO_REPLY;
    }

    @Override
    public RespValue sync(Client client, List<byte[]> argv) {
        // 이미 레플리카면 무시
        if (client.isReplica()) {
            return RespValue.NO_REPLY;
        }
        boolean psync = text(argv.get(0)).equalsIgnoreCase("psync");
        if (argv.size() > 3 && psync && text(argv.get(3)).equalsIgnoreCase("failover")) {
            log("레플리카 ID %s 로 페일오버 요청을 받았습니다", text(argv.get(1)));
            return new RespValue.Err("ERR PSYNC FAILOVER can't be sent to a master.");
        }
        // 묶음 앞 명령의 응답이 아직 안 나감 - 버퍼가 섞임
        if (client.hasPendingReplies()) {
            return new RespValue.Err("ERR SYNC and PSYNC are invalid with pending output");
        }
        if ((client.replicaReq() & Client.REQ_RDB_MASK) != 0 && (client.replicaCapa() & Client.CAPA_EOF) == 0) {
            return new RespValue.Err("ERR Filtered replica requires EOF capability");
        }

        ReplicaLink slave = newLink(client);
        log("레플리카 %s 가 동기화를 요청했습니다", slave.name());

        if (psync) {
            OptionalLong offset = Numbers.parseLong(argv.get(2));
            if (offset.isEmpty()) {
                log("레플리카 %s 가 잘못된 오프셋으로 동기화를 요청했습니다", slave.name());
                slave.writer.stop();
                return Errors.notAnInteger();
            }
            if (tryPartialResync(slave, text(argv.get(1)), offset.getAsLong())) {
                statSyncPartialOk++;
                return RespValue.NO_REPLY;
            }
            if (argv.get(1).length == 0 || argv.get(1)[0] != '?') {
                statSyncPartialErr++;
            }
        } else {
            // 옛 프로토콜 (redis-cli --replica 등) - ACK 안 옴
            slave.prePsync = true;
        }

        // 전체 동기화
        statSyncFull++;
        slave.state = ReplicaLink.State.WAIT_BGSAVE_START;
        attach(slave);

        // 백로그를 처음 만들 때는 새 복제 이력 - 새 ID
        if (slaves.size() == 1 && !buffer.hasBacklog()) {
            changeReplicationId();
            clearReplicationId2();
            buffer.createBacklog();
            log("복제 백로그를 만들었습니다. 새 복제 ID 는 '%s', '%s' 입니다", replid, replid2);
        }

        if (children.diskBgsaveActive()) {
            // 1) 디스크 BGSAVE 진행 중 - 그 시점부터 차이를 쌓는 레플리카가 있으면 같이 탐
            ReplicaLink same = null;
            for (ReplicaLink other : slaves) {
                if (other.state == ReplicaLink.State.WAIT_BGSAVE_END
                        && (!other.client.rdbOnly() || client.rdbOnly())) {
                    same = other;
                    break;
                }
            }
            if (same != null
                    && (client.replicaCapa() & same.client.replicaCapa()) == same.client.replicaCapa()
                    && client.replicaReq() == same.client.replicaReq()) {
                if (!client.rdbOnly()) {
                    buffer.copyReference(same, slave);
                }
                setupFullResync(slave, same.psyncInitialOffset);
                log("진행 중인 BGSAVE 가 끝나기를 기다립니다");
            } else {
                log("진행 중인 BGSAVE 에 붙을 수 없어 다음 BGSAVE 를 기다립니다");
            }
        } else if (socketTransfer != null) {
            // 2) 디스크리스 전송 중 - 다음 차례
            log("진행 중인 BGSAVE 가 소켓 대상이라 다음 BGSAVE 를 기다립니다");
        } else if (config.disklessSync() && (client.replicaCapa() & Client.CAPA_EOF) != 0
                && config.disklessSyncDelay() > 0) {
            // 3) 디스크리스 - 다른 레플리카를 기다렸다가 replicationCron 에서 시작
            log("디스크리스 동기화용 BGSAVE 를 잠시 미룹니다");
        } else if (!hasActiveChildProcess()) {
            startBgsaveForReplication(client.replicaCapa(), client.replicaReq());
        } else {
            log("다른 백그라운드 작업이 있어 복제용 BGSAVE 를 미룹니다");
        }
        return RespValue.NO_REPLY;
    }

    @Override
    public RespValue role() {
        List<RespValue> replicas = new ArrayList<>();
        for (ReplicaLink slave : slaves) {
            String ip = slave.client.announcedIp() != null ? slave.client.announcedIp() : slave.client.peerIp();
            if (ip == null || slave.state != ReplicaLink.State.ONLINE) {
                continue;
            }
            replicas.add(RespValue.Array.of(
                    RespValue.BulkString.of(ip),
                    RespValue.BulkString.of(Long.toString(slave.client.listeningPort())),
                    RespValue.BulkString.of(Long.toString(slave.ackOffset))));
        }
        return RespValue.Array.of(
                RespValue.BulkString.of("master"),
                new RespValue.Int(buffer.masterReplOffset()),
                new RespValue.Array(replicas));
    }

    // ---------------------------------------------------------------- 동기화

    /** masterTryPartialResynchronization - 백로그에 원하는 위치가 있으면 +CONTINUE */
    private boolean tryPartialResync(ReplicaLink slave, String requestedId, long offset) {
        if (!requestedId.equalsIgnoreCase(replid)
                && (!requestedId.equalsIgnoreCase(replid2) || offset > secondReplidOffset)) {
            if (!requestedId.startsWith("?")) {
                if (!requestedId.equalsIgnoreCase(replid) && !requestedId.equalsIgnoreCase(replid2)) {
                    log("부분 동기화 거절: 복제 ID 불일치 (레플리카 요청 '%s', 내 ID '%s', '%s')",
                            requestedId, replid, replid2);
                } else {
                    log("부분 동기화 거절: 두 번째 ID 의 요청 오프셋 %d, 응할 수 있는 건 %d 까지",
                            offset, secondReplidOffset);
                }
            } else {
                log("레플리카 %s 가 전체 동기화를 요청했습니다", slave.name());
            }
            return false;
        }
        if (!buffer.hasBacklog() || offset < buffer.backlogOffset()
                || offset > buffer.backlogOffset() + buffer.histlen()) {
            log("백로그가 모자라 레플리카 %s 와 부분 동기화할 수 없습니다 (요청 %d)", slave.name(), offset);
            if (offset > buffer.masterReplOffset()) {
                log("경고: 레플리카 %s 가 마스터 오프셋보다 큰 오프셋으로 PSYNC 했습니다", slave.name());
            }
            return false;
        }

        slave.state = ReplicaLink.State.ONLINE;
        slave.ackTime = clock.getAsLong();
        slave.startStreamOnAck = false;
        attach(slave);
        boolean psync2 = (slave.client.replicaCapa() & Client.CAPA_PSYNC2) != 0;
        slave.writer.outOfBand(ascii(psync2 ? "+CONTINUE " + replid + "\r\n" : "+CONTINUE\r\n"));
        slave.streamFrom(offset - 1);
        long length = buffer.referenceFrom(slave, offset);
        log("레플리카 %s 의 부분 동기화를 받아들였습니다. 오프셋 %d 부터 백로그 %d 바이트를 보냅니다",
                slave.name(), offset, length);
        return true;
    }

    /**
     * replicationSetupSlaveForFullResync - 이 오프셋부터의 차이를 쌓기 시작
     * SELECT 를 다시 내도록 slaveseldb 초기화
     */
    private void setupFullResync(ReplicaLink slave, long offset) {
        slave.psyncInitialOffset = offset;
        slave.state = ReplicaLink.State.WAIT_BGSAVE_END;
        slave.streamFrom(offset);
        slaveseldb = -1;
        if (!slave.prePsync) {
            slave.writer.outOfBand(ascii("+FULLRESYNC " + replid + " " + offset + "\r\n"));
        }
    }

    /** startBgsaveForReplication - 디스크리스면 소켓, 아니면 dump.rdb */
    private void startBgsaveForReplication(int mincapa, int req) {
        boolean socketTarget = (config.disklessSync() || (req & Client.REQ_RDB_MASK) != 0)
                && (mincapa & Client.CAPA_EOF) != 0;
        log("복제용 BGSAVE 를 시작합니다. 대상: %s", socketTarget ? "레플리카 소켓" : "디스크");

        boolean started = socketTarget ? saveToReplicaSockets(req) : children.startDiskBgsave();
        if (!started) {
            log("복제용 BGSAVE 실패");
            for (ReplicaLink slave : List.copyOf(slaves)) {
                if (slave.state == ReplicaLink.State.WAIT_BGSAVE_START) {
                    detach(slave);
                    slave.client.replica(null);
                    slave.writer.outOfBand(ascii("-ERR BGSAVE failed, replication can't continue\r\n"));
                    slave.writer.closeAfterWrites();
                }
            }
            return;
        }
        if (!socketTarget) {
            for (ReplicaLink slave : slaves) {
                if (slave.state == ReplicaLink.State.WAIT_BGSAVE_START && slave.client.replicaReq() == req) {
                    setupFullResync(slave, buffer.masterReplOffset());
                }
            }
        }
    }

    /**
     * rdbSaveToSlavesSockets - 직렬화는 지금 (fork 시점), EOF·CRC64 와 전송은 다른 스레드
     * 받은 레플리카 모두 다 쓰면(또는 끊기면) 끝
     */
    private boolean saveToReplicaSockets(int req) {
        if (hasActiveChildProcess()) {
            return false;
        }
        List<ReplicaLink> targets = new ArrayList<>();
        for (ReplicaLink slave : slaves) {
            if (slave.state == ReplicaLink.State.WAIT_BGSAVE_START && slave.client.replicaReq() == req) {
                targets.add(slave);
                setupFullResync(slave, buffer.masterReplOffset());
            }
        }
        Runtime runtime = Runtime.getRuntime();
        RdbWriter.Body body = RdbWriter.serialize(keyspace, clock.getAsLong(),
                runtime.totalMemory() - runtime.freeMemory(), false, saveInfo(),
                (req & Client.REQ_RDB_EXCLUDE_DATA) == 0);
        String mark = randomHex(EOF_MARK_SIZE);
        byte[] prefix = ascii("$EOF:" + mark + "\r\n");
        byte[] suffix = ascii(mark);

        CompletableFuture<byte[]> payload = CompletableFuture.supplyAsync(body::finish, rdbThread);
        List<CompletableFuture<Void>> transfers = new ArrayList<>();
        for (ReplicaLink slave : targets) {
            CompletableFuture<Void> done = new CompletableFuture<>();
            transfers.add(done);
            payload.whenComplete((bytes, error) -> {
                if (error != null) {
                    done.completeExceptionally(error);
                } else {
                    slave.writer.rdb(prefix, bytes, suffix, done);
                }
            });
        }
        // 레플리카별 실패는 그 레플리카만 끊음 - 전송 자체는 끝난 것으로
        socketTransfer = CompletableFuture.allOf(transfers.stream()
                .map(f -> f.exceptionally(e -> null))
                .toArray(CompletableFuture[]::new));
        return true;
    }

    /** checkChildrenDone 의 소켓 쪽 - 전송이 끝났으면 레플리카를 온라인으로 */
    public void checkSocketChildDone() {
        if (socketTransfer != null && socketTransfer.isDone()) {
            socketTransfer = null;
            backgroundSaveDone(true, true);
        }
    }

    /**
     * updateSlavesWaitingBgsave - BGSAVE 가 끝남
     * 소켓이면 이미 다 보냄 → 온라인 (스트림은 첫 ACK 뒤), 디스크면 파일 전송 시작
     */
    public void backgroundSaveDone(boolean ok, boolean socket) {
        for (ReplicaLink slave : List.copyOf(slaves)) {
            if (slave.state != ReplicaLink.State.WAIT_BGSAVE_END) {
                continue;
            }
            if (!ok) {
                log("동기화 실패: BGSAVE 가 실패했습니다");
                free(slave);
                continue;
            }
            if (socket) {
                log("레플리카 %s 로 RDB 를 소켓으로 다 보냈습니다. 스트림은 REPLCONF ACK 를 받은 뒤 시작합니다",
                        slave.name());
                if (!putOnline(slave)) {
                    free(slave);
                    continue;
                }
                slave.startStreamOnAck = true;
            } else {
                FileChannel channel;
                long size;
                try {
                    channel = FileChannel.open(children.rdbFile(), StandardOpenOption.READ);
                    size = channel.size();
                } catch (IOException e) {
                    log("동기화 실패: BGSAVE 뒤 RDB 를 열 수 없습니다: %s", e.getMessage());
                    free(slave);
                    continue;
                }
                slave.state = ReplicaLink.State.SEND_BULK;
                slave.writer.file(ascii("$" + size + "\r\n"), channel,
                        () -> executor.accept(() -> bulkSent(slave)));
            }
        }
    }

    /** sendBulkToSlave 끝 - 파일 다 보냄 */
    private void bulkSent(ReplicaLink slave) {
        if (slave.freed) {
            return;
        }
        if (!putOnline(slave)) {
            free(slave);
            return;
        }
        startCommandStream(slave);
    }

    /** replicaPutOnline - rdb-only 면 false (끊을 차례) */
    private boolean putOnline(ReplicaLink slave) {
        if (slave.client.rdbOnly()) {
            slave.state = ReplicaLink.State.RDB_TRANSMITTED;
            log("RDB 전송 완료 - RDB 만 원하는 레플리카 %s 는 곧 끊습니다", slave.name());
            return false;
        }
        slave.state = ReplicaLink.State.ONLINE;
        slave.ackTime = clock.getAsLong();
        log("레플리카 %s 와 동기화 성공", slave.name());
        return true;
    }

    /** replicaStartCommandStream - 쌓아 둔 스트림을 이제 보냄 */
    private void startCommandStream(ReplicaLink slave) {
        slave.startStreamOnAck = false;
    }

    // ---------------------------------------------------------------- 주기 작업

    /**
     * beforeSleep 의 handleClientsWithPendingWrites - 레플리카 몫
     * 다 쓴 블록 참조 넘기기 → 새 스트림 바이트 넘기기
     */
    public void beforeSleep() {
        for (ReplicaLink slave : List.copyOf(slaves)) {
            if (!slave.canFeed() || slave.freed) {
                continue;
            }
            buffer.advance(slave, slave.writer.written());
            if (!slave.streaming()) {
                continue;
            }
            long end = buffer.masterReplOffset();
            if (end > slave.queuedOffset && buffer.hasBacklog()) {
                slave.writer.stream(buffer.copySince(slave.queuedOffset), end);
                slave.queuedOffset = end;
            }
        }
    }

    /** replicationCron 의 마스터 부분 - 1초마다 */
    public void cron() {
        long now = clock.getAsLong();

        // 스트림에 PING - 레플리카가 마스터 타임아웃을 잴 수 있게 (오프셋도 늘어남), shared.ping 은 소문자
        if (cronLoops % config.pingPeriod() == 0 && !slaves.isEmpty()) {
            feed(-1, List.of(ascii("ping")));
        }

        // RDB 를 기다리는 레플리카엔 줄바꿈 - 오프셋 밖, 연결만 살림
        for (ReplicaLink slave : slaves) {
            boolean presync = slave.state == ReplicaLink.State.WAIT_BGSAVE_START
                    || (slave.state == ReplicaLink.State.WAIT_BGSAVE_END && socketTransfer == null);
            if (presync) {
                slave.writer.outOfBand(NEWLINE);
            }
        }

        // 타임아웃
        for (ReplicaLink slave : List.copyOf(slaves)) {
            if (slave.state == ReplicaLink.State.ONLINE) {
                if (slave.prePsync) {
                    continue;
                }
                if (now - slave.ackTime > config.timeout()) {
                    log("응답 없는 레플리카를 끊습니다 (스트리밍): %s", slave.name());
                    free(slave);
                    continue;
                }
            }
            // 디스크리스 전송이 막힌 레플리카 - 쓰기가 끝나지 않은 채 repl-timeout 넘김
            if (slave.state == ReplicaLink.State.WAIT_BGSAVE_END && socketTransfer != null) {
                long started = slave.writer.writeStartedAt();
                if (started != 0 && now - started > config.timeout()) {
                    log("응답 없는 레플리카를 끊습니다 (전체 동기화): %s", slave.name());
                    free(slave);
                }
            }
        }

        // 레플리카 없이 오래되면 백로그 해제 - 이후 쓰기가 오프셋을 안 늘리므로 ID 도 새로
        if (slaves.isEmpty() && config.backlogTtl() > 0 && buffer.hasBacklog()) {
            long idle = now - noSlavesSince;
            if (idle > config.backlogTtl()) {
                changeReplicationId();
                clearReplicationId2();
                buffer.freeBacklog();
                log("레플리카 없이 %d초가 지나 복제 백로그를 해제했습니다", config.backlogTtl());
            }
        }

        startPendingFork();
        cronLoops++;
    }

    /** replicationStartPendingFork - 기다리는 레플리카가 있고, 디스크리스면 지연이 지났을 때 */
    private void startPendingFork() {
        if (hasActiveChildProcess()) {
            return;
        }
        long now = clock.getAsLong();
        long maxIdle = 0;
        int waiting = 0;
        int mincapa = 0;
        int req = 0;
        boolean first = true;
        for (ReplicaLink slave : slaves) {
            if (slave.state != ReplicaLink.State.WAIT_BGSAVE_START) {
                continue;
            }
            if (first) {
                req = slave.client.replicaReq();
            } else if (req != slave.client.replicaReq()) {
                continue;
            }
            maxIdle = Math.max(maxIdle, now - slave.client.lastInteraction());
            waiting++;
            mincapa = first ? slave.client.replicaCapa() : (mincapa & slave.client.replicaCapa());
            first = false;
        }
        if (waiting > 0 && (!config.disklessSync()
                || (config.disklessSyncMaxReplicas() > 0 && waiting >= config.disklessSyncMaxReplicas())
                || maxIdle >= config.disklessSyncDelay())) {
            startBgsaveForReplication(mincapa, req);
        }
    }

    // ---------------------------------------------------------------- 연결

    /** 연결이 끊김 - 커넥션 스레드가 알림 */
    public void clientClosed(Client client) {
        if (client.replica() instanceof ReplicaLink slave) {
            free(slave);
        }
    }

    /** 레플리카 연결로 온 명령이 응답을 만들었음 - 복제 링크는 응답용이 아니라 끊음 */
    public void replicaGeneratedReply(Client client, String command) {
        if (client.replica() instanceof ReplicaLink slave) {
            log("레플리카 %s 가 응답이 있는 명령 '%s' 를 보내 연결을 끊습니다", slave.name(), command);
            free(slave);
        }
    }

    /** 서버 종료 */
    public void close() {
        for (ReplicaLink slave : List.copyOf(slaves)) {
            free(slave);
        }
        rdbThread.shutdownNow();
    }

    private ReplicaLink newLink(Client client) {
        ReplicaLink[] holder = new ReplicaLink[1];
        Socket socket = client.socket();
        ReplicaWriter writer;
        try {
            writer = new ReplicaWriter("glass-redis-replica-" + client.id(),
                    socket == null ? java.io.OutputStream.nullOutputStream() : socket.getOutputStream(),
                    clock, client::close, () -> executor.accept(() -> free(holder[0])));
        } catch (IOException e) {
            throw new IllegalStateException("레플리카 소켓을 쓸 수 없음", e);
        }
        ReplicaLink slave = new ReplicaLink(client, writer);
        holder[0] = slave;
        writer.start(buffer.masterReplOffset());
        return slave;
    }

    private void attach(ReplicaLink slave) {
        slave.client.replica(slave);
        slaves.add(slave);
    }

    private void detach(ReplicaLink slave) {
        if (slaves.remove(slave)) {
            buffer.release(slave);
            if (slaves.isEmpty()) {
                noSlavesSince = clock.getAsLong();
            }
        }
    }

    /** freeClient 의 레플리카 부분 */
    private void free(ReplicaLink slave) {
        if (slave == null || slave.freed) {
            return;
        }
        slave.freed = true;
        // 이 RDB 를 기다리는 레플리카가 더는 없으면 디스크 BGSAVE 중단 - save 규칙 있으면 파일이 중요하니 둠
        if (children.saveRulesEmpty() && slave.state == ReplicaLink.State.WAIT_BGSAVE_END
                && children.diskBgsaveActive() && !anyOtherWaitingRdb(slave)) {
            children.killDiskBgsave();
        }
        detach(slave);
        slave.writer.stop();
        slave.client.close();
    }

    /** anyOtherSlaveWaitRdb */
    private boolean anyOtherWaitingRdb(ReplicaLink except) {
        for (ReplicaLink slave : slaves) {
            if (slave != except && slave.state == ReplicaLink.State.WAIT_BGSAVE_END) {
                return true;
            }
        }
        return false;
    }

    /** checkClientOutputBufferLimits - 새 블록이 생길 때만 */
    private void checkOutputBufferLimit(ReplicationBuffer.Ref ref) {
        ReplicaLink slave = (ReplicaLink) ref;
        if (slave.freed || slave.closeAsap) {
            return;
        }
        long used = buffer.outputBufferUsage(slave);
        boolean hard = config.outputHardLimit() > 0 && used >= config.outputHardLimit();
        boolean soft = config.outputSoftLimit() > 0 && used >= config.outputSoftLimit();
        long now = clock.getAsLong();
        if (soft) {
            if (slave.softLimitReachedTime == 0) {
                slave.softLimitReachedTime = now;
                soft = false;
            } else if (now - slave.softLimitReachedTime <= config.outputSoftSeconds()) {
                soft = false;
            }
        } else {
            slave.softLimitReachedTime = 0;
        }
        if (soft || hard) {
            log("레플리카 %s 의 출력 버퍼가 한도를 넘어 곧 끊습니다", slave.name());
            // 버퍼 순회 중이라 끊기는 나중에 (freeClientAsync)
            slave.closeAsap = true;
            executor.accept(() -> free(slave));
        }
    }

    private boolean hasActiveChildProcess() {
        return children.active() || socketTransfer != null;
    }

    /** changeReplicationId */
    private void changeReplicationId() {
        replid = randomHex(ID_SIZE);
    }

    /** clearReplicationId2 */
    private void clearReplicationId2() {
        replid2 = "0".repeat(ID_SIZE);
        secondReplidOffset = -1;
    }

    // ---------------------------------------------------------------- 대시보드·테스트용

    public int replicaCount() {
        return slaves.size();
    }

    public long backlogHistlen() {
        return buffer.histlen();
    }

    public long backlogFirstByteOffset() {
        return buffer.backlogOffset();
    }

    public boolean hasBacklog() {
        return buffer.hasBacklog();
    }

    public long syncFull() {
        return statSyncFull;
    }

    public long syncPartialOk() {
        return statSyncPartialOk;
    }

    public long syncPartialErr() {
        return statSyncPartialErr;
    }

    // ---------------------------------------------------------------- 도움

    /** getRandomHexChars */
    static String randomHex(int length) {
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            out.append(Character.forDigit(RANDOM.nextInt(16), 16));
        }
        return out.toString();
    }

    /** *<n>\r\n$<len>\r\n<arg>\r\n... */
    private static byte[] command(List<byte[]> argv) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(ascii("*" + argv.size() + "\r\n"));
        for (byte[] arg : argv) {
            out.writeBytes(ascii("$" + arg.length + "\r\n"));
            out.writeBytes(arg);
            out.writeBytes(ascii("\r\n"));
        }
        return out.toByteArray();
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    /** 인자 바이트 그대로 - 에러 메시지에 되돌려 보낼 때 */
    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    private static void log(String format, Object... args) {
        System.out.println("[glass-redis] " + String.format(format, args));
    }
}
