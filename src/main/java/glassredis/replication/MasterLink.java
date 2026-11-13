package glassredis.replication;

import glassredis.resp.RespProtocolException;
import glassredis.resp.RespReader;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * 레플리카 → 마스터 연결 하나 - syncWithMaster, readSyncBulkPayload, 마스터 클라이언트 읽기
 * 소켓 블로킹 입출력은 이 가상 스레드, 상태 변경은 전부 실행 스레드 (owner 의 메서드를 큐로)
 *
 * <pre>
 *   접속 → PING → REPLCONF listening-port / capa eof psync2 → PSYNC
 *        ├ +FULLRESYNC → RDB 를 임시 파일로 → 실행 스레드가 비우고 로드 ─┐
 *        └ +CONTINUE  → 끊긴 자리부터 ──────────────────────────────┴→ 스트림 읽어 실행 스레드로
 * </pre>
 */
final class MasterLink {

    /** 스트림에서 읽은 명령 하나 - consumed 는 이 명령 끝까지의 스트림 바이트 수 */
    record Received(List<byte[]> argv, long consumed) {
    }

    private final Replicator owner;
    private final String host;
    private final int port;
    private final Consumer<Runnable> executor;
    private final LongSupplier clock;
    /** RDB 임시 파일 폴더 (dir) */
    private final Path dir;
    private final long timeoutSeconds;
    private final Thread thread;

    private volatile Socket socket;
    private volatile boolean closed;
    /** repl_transfer_lastio - 핸드셰이크·전송 타임아웃 */
    private volatile long lastIo;
    /** 스트림 단계의 쓰기 - ACK */
    private volatile ReplicaWriter writer;
    /** 받는 중인 임시 파일 - 취소 시 삭제 */
    private volatile Path tempFile;

    MasterLink(Replicator owner, String host, int port, Consumer<Runnable> executor, LongSupplier clock, Path dir,
               long timeoutSeconds) {
        this.owner = owner;
        this.host = host;
        this.port = port;
        this.executor = executor;
        this.clock = clock;
        this.dir = dir;
        this.timeoutSeconds = timeoutSeconds;
        this.lastIo = clock.getAsLong();
        this.thread = Thread.ofVirtual().name("glass-redis-master-link").unstarted(this::run);
    }

    void start() {
        thread.start();
    }

    long lastIo() {
        return lastIo;
    }

    /** 스트림 단계에서만 - 그 전엔 null */
    ReplicaWriter writer() {
        return writer;
    }

    /** 연결 끊기 - 받던 임시 파일 삭제 (replicationAbortSyncTransfer) */
    void close() {
        closed = true;
        Socket open = socket;
        if (open != null) {
            try {
                open.close();
            } catch (IOException ignored) {
                // 이미 닫힘
            }
        }
        ReplicaWriter w = writer;
        if (w != null) {
            w.stop();
        }
        thread.interrupt();
        deleteTemp();
    }

    private void run() {
        String failure;
        try {
            failure = session();
        } catch (IOException e) {
            failure = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        } catch (InterruptedException e) {
            return;
        }
        if (!closed) {
            String reason = failure;
            executor.accept(() -> owner.linkFailed(this, reason));
        }
        deleteTemp();
    }

    /** @return 끝난 이유 */
    private String session() throws IOException, InterruptedException {
        Socket s = new Socket();
        socket = s;
        try {
            s.connect(new InetSocketAddress(host, port), (int) Math.max(1000, timeoutSeconds * 1000));
        } catch (IOException e) {
            return "Error condition on socket for SYNC: " + e.getMessage();
        }
        s.setTcpNoDelay(true);
        touch();
        if (!call(() -> owner.linkConnected(this))) {
            return null;
        }
        InputStream in = new BufferedInputStream(s.getInputStream(), RespReader.IO_BUFFER_SIZE);
        OutputStream out = s.getOutputStream();

        // PING - +PONG, 또는 인증 관련 에러면 계속
        send(out, "PING");
        String pong = line(in);
        if (!pong.startsWith("+") && !pong.startsWith("-NOAUTH") && !pong.startsWith("-NOPERM")
                && !pong.startsWith("-ERR operation not permitted")) {
            return "Error reply to PING from master: '" + pong + "'";
        }
        log("마스터가 PING 에 응답했습니다. 복제를 이어 갑니다");

        // 두 REPLCONF 를 한 번에 보내고 응답을 차례로 - 에러여도 계속
        int listeningPort = call(owner::listeningPort);
        send(out, "REPLCONF", "listening-port", Integer.toString(listeningPort));
        send(out, "REPLCONF", "capa", "eof", "capa", "psync2");
        String portReply = line(in);
        if (portReply.startsWith("-")) {
            log("(무시) 마스터가 REPLCONF listening-port 를 모릅니다: %s", portReply);
        }
        String capaReply = line(in);
        if (capaReply.startsWith("-")) {
            log("(무시) 마스터가 REPLCONF capa 를 모릅니다: %s", capaReply);
        }

        // PSYNC - 캐시된 마스터가 있으면 이어 받기 시도
        String[] psync = call(owner::psyncArgs);
        send(out, "PSYNC", psync[0], psync[1]);
        String reply;
        do {
            reply = line(in);
        } while (reply.isEmpty());

        long streamStart;
        if (reply.startsWith("+FULLRESYNC")) {
            String[] parts = reply.split(" ");
            String id = parts.length > 2 && parts[1].length() == Replicator.ID_SIZE ? parts[1] : null;
            long offset = id == null ? -1 : parseOffset(parts[2]);
            if (!call(() -> owner.fullResyncStarted(this, id, offset))) {
                return null;
            }
            if (!receiveRdb(in)) {
                return null;
            }
            streamStart = offset;
        } else if (reply.startsWith("+CONTINUE")) {
            String rest = reply.substring(9).trim();
            String newId = rest.length() == Replicator.ID_SIZE ? rest : null;
            writer = newWriter(out);
            Long start = call(() -> owner.continueResync(this, newId));
            if (start == null) {
                return null;
            }
            streamStart = start;
        } else if (reply.startsWith("-NOMASTERLINK") || reply.startsWith("-LOADING")) {
            return "Master is currently unable to PSYNC but should be in the future: " + reply;
        } else {
            log(reply.startsWith("-ERR") ? "마스터가 PSYNC 를 지원하지 않거나 에러 상태입니다 (응답: %s)"
                    : "PSYNC 에 예상 밖 응답: %s", reply);
            // 옛 방식 - SYNC 후 RDB, 오프셋 없음
            out.write("SYNC\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            if (!call(() -> owner.fullResyncStarted(this, null, -1))) {
                return null;
            }
            if (!receiveRdb(in)) {
                return null;
            }
            streamStart = -1;
        }
        stream(in, streamStart);
        return "Connection with master lost.";
    }

    /**
     * readSyncBulkPayload - $<길이> 또는 $EOF:<표식>, 앞 줄바꿈은 살아 있다는 신호
     * 다 받으면 실행 스레드가 비우고 로드할 때까지 대기
     */
    private boolean receiveRdb(InputStream in) throws IOException, InterruptedException {
        String header;
        do {
            header = line(in);
        } while (header.isEmpty());
        if (header.startsWith("-")) {
            throw new IOException("MASTER aborted replication with an error: " + header.substring(1));
        }
        if (!header.startsWith("$")) {
            throw new IOException("Bad protocol from MASTER, the first byte is not '$' (we received '"
                    + header + "'), are you sure the host and port are right?");
        }
        boolean eofMark = header.startsWith("$EOF:");
        byte[] mark = eofMark ? header.substring(5).getBytes(StandardCharsets.US_ASCII) : null;
        long size = eofMark ? -1 : Long.parseLong(header.substring(1));
        log(eofMark ? "MASTER <-> REPLICA 동기화: 마스터에게서 EOF 형식 RDB 를 디스크로 받습니다"
                : "MASTER <-> REPLICA 동기화: 마스터에게서 %d 바이트를 디스크로 받습니다", size);

        Path temp = dir.resolve("temp-" + clock.getAsLong() + "." + ProcessHandle.current().pid() + ".rdb");
        tempFile = temp;
        Files.createDirectories(dir);
        try (FileChannel file = FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            byte[] buf = new byte[RespReader.IO_BUFFER_SIZE];
            if (!eofMark) {
                long left = size;
                while (left > 0) {
                    int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                    if (n < 0) {
                        throw new EOFException("I/O error trying to sync with MASTER: connection lost");
                    }
                    file.write(java.nio.ByteBuffer.wrap(buf, 0, n));
                    left -= n;
                    touch();
                }
            } else {
                // 끝 40바이트가 표식이면 끝 - 표식은 파일에 안 씀
                byte[] tail = new byte[0];
                while (true) {
                    int n = in.read(buf);
                    if (n < 0) {
                        throw new EOFException("I/O error trying to sync with MASTER: connection lost");
                    }
                    touch();
                    byte[] joined = Arrays.copyOf(tail, tail.length + n);
                    System.arraycopy(buf, 0, joined, tail.length, n);
                    if (joined.length >= mark.length && Arrays.equals(joined, joined.length - mark.length,
                            joined.length, mark, 0, mark.length)) {
                        file.write(java.nio.ByteBuffer.wrap(joined, 0, joined.length - mark.length));
                        break;
                    }
                    // 표식이 두 read 에 걸칠 수 있어 끝 40바이트는 남겨 둠
                    int keep = Math.min(joined.length, mark.length);
                    file.write(java.nio.ByteBuffer.wrap(joined, 0, joined.length - keep));
                    tail = Arrays.copyOfRange(joined, joined.length - keep, joined.length);
                }
            }
            file.force(true);
        }
        // 비우고 로드하는 동안 받는 쪽은 멈춤 - 그 뒤 바로 스트림
        writer = newWriter(socket.getOutputStream());
        boolean loaded = call(() -> owner.loadFullSync(this, temp));
        tempFile = null;
        return loaded;
    }

    /** 마스터 클라이언트 - 받은 명령을 묶어 실행 스레드로, 처리할 때까지 다음 읽기 보류 */
    private void stream(InputStream in, long streamStart) throws IOException, InterruptedException {
        RespReader reader = new RespReader(in);
        reader.record();
        List<Received> batch = new ArrayList<>();
        while (!closed) {
            batch.clear();
            do {
                List<byte[]> argv;
                try {
                    argv = reader.readCommand();
                } catch (RespProtocolException e) {
                    String message = "Protocol error from master: " + e.getMessage();
                    executor.accept(() -> owner.masterProtocolError(this, message));
                    closed = true;
                    return;
                }
                if (argv == null) {
                    if (!batch.isEmpty()) {
                        deliver(batch, reader);
                    }
                    return;
                }
                touch();
                batch.add(new Received(argv, reader.consumed()));
            } while (reader.hasBufferedCommand());
            deliver(batch, reader);
        }
    }

    private void deliver(List<Received> batch, RespReader reader) throws InterruptedException, IOException {
        List<Received> commands = List.copyOf(batch);
        byte[] raw = reader.takeRecorded();
        call(() -> {
            owner.applyFromMaster(this, commands, raw);
            return null;
        });
    }

    private ReplicaWriter newWriter(OutputStream out) {
        ReplicaWriter w = new ReplicaWriter("glass-redis-master-ack", out, clock, this::closeSocket,
                () -> executor.accept(() -> owner.linkFailed(this, "Connection with master lost.")));
        w.start(0);
        return w;
    }

    private void closeSocket() {
        Socket open = socket;
        if (open != null) {
            try {
                open.close();
            } catch (IOException ignored) {
                // 이미 닫힘
            }
        }
    }

    /** 실행 스레드에서 돌리고 결과 대기 */
    private <T> T call(Callable<T> task) throws InterruptedException, IOException {
        CompletableFuture<T> result = new CompletableFuture<>();
        executor.accept(() -> {
            try {
                result.complete(task.call());
            } catch (Throwable e) {
                result.completeExceptionally(e);
            }
        });
        try {
            return result.get();
        } catch (ExecutionException e) {
            throw new IOException("실행 스레드 처리 실패: " + e.getCause(), e.getCause());
        }
    }

    private void touch() {
        lastIo = clock.getAsLong();
    }

    private void deleteTemp() {
        Path temp = tempFile;
        if (temp != null) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // 다음 동기화가 다른 이름으로
            }
            tempFile = null;
        }
    }

    private static long parseOffset(String text) {
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void send(OutputStream out, String... args) throws IOException {
        StringBuilder b = new StringBuilder("*").append(args.length).append("\r\n");
        for (String arg : args) {
            b.append('$').append(arg.getBytes(StandardCharsets.ISO_8859_1).length).append("\r\n")
                    .append(arg).append("\r\n");
        }
        out.write(b.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    /** CRLF 뺀 한 줄 - receiveSynchronousResponse, 읽는 동안 타임아웃 기준 갱신 */
    private String line(InputStream in) throws IOException {
        StringBuilder b = new StringBuilder();
        int ch;
        while ((ch = in.read()) != '\n') {
            if (ch < 0) {
                throw new EOFException("Connection with master lost.");
            }
            b.append((char) ch);
        }
        touch();
        int n = b.length();
        if (n > 0 && b.charAt(n - 1) == '\r') {
            b.setLength(n - 1);
        }
        return b.toString();
    }

    private static void log(String format, Object... args) {
        System.out.println("[glass-redis] " + String.format(format, args));
    }
}
