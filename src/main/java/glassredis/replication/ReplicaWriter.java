package glassredis.replication;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 레플리카 소켓 쓰기 전용 가상 스레드 - 실행 스레드는 넘기기만, 블로킹 쓰기는 여기서
 * 넘긴 순서대로 씀 - +FULLRESYNC → RDB → 명령 스트림 순서 보장
 * Redis 는 실행 스레드가 논블로킹으로 직접 씀, 여기선 커넥션 모델(가상 스레드 블로킹)을 따라 분리
 */
final class ReplicaWriter {

    /** 넘길 것 하나 */
    private sealed interface Item {
    }

    /**
     * @param streamEnd 복제 스트림이면 마지막 바이트의 오프셋, 스트림 밖 바이트(+FULLRESYNC, 줄바꿈)면 -1
     */
    private record Bytes(byte[] data, long streamEnd) implements Item {
    }

    /** 디스크 RDB - "$<길이>\r\n" + 파일 내용 (sendBulkToSlave) */
    private record File(byte[] preamble, FileChannel channel, Runnable onDone) implements Item {
    }

    /** 디스크리스 RDB - "$EOF:<표식>\r\n" + 본문 + 표식 (rdbSaveRioWithEOFMark) */
    private record Rdb(byte[] prefix, byte[] payload, byte[] suffix, CompletableFuture<Void> done) implements Item {
    }

    /** 앞의 것을 다 쓰고 닫기 - CLIENT_CLOSE_AFTER_REPLY */
    private record CloseAfter() implements Item {
    }

    private final BlockingQueue<Item> queue = new LinkedBlockingQueue<>();
    private final OutputStream out;
    private final LongSupplier clock;
    /** 소켓 닫기 - 읽기 스레드도 깨움 */
    private final Runnable closeSocket;
    /** 쓰기 실패 - 실행 스레드에 freeClient 요청 */
    private final Runnable onFailure;
    private final Thread thread;

    /** 다 쓴 스트림 위치 - 실행 스레드가 블록 참조를 옮길 때 읽음 */
    private final AtomicLong written = new AtomicLong();
    /** 쓰기 호출이 안 끝난 채 시작된 시각 (초), 쉬는 중이면 0 - repl_last_partial_write 대신 */
    private volatile long writeStartedAt;

    private boolean stopped;

    ReplicaWriter(String name, OutputStream out, LongSupplier clock, Runnable closeSocket, Runnable onFailure) {
        this.out = new BufferedOutputStream(out, 16 * 1024);
        this.clock = clock;
        this.closeSocket = closeSocket;
        this.onFailure = onFailure;
        this.thread = Thread.ofVirtual().name(name).unstarted(this::run);
    }

    void start(long initialOffset) {
        written.set(initialOffset);
        thread.start();
    }

    long written() {
        return written.get();
    }

    /** 위치만 옮김 - 부분 동기화·전체 동기화 시작점 */
    void resetWritten(long offset) {
        written.set(offset);
    }

    long writeStartedAt() {
        return writeStartedAt;
    }

    void outOfBand(byte[] data) {
        enqueue(new Bytes(data, -1));
    }

    void stream(byte[] data, long streamEnd) {
        enqueue(new Bytes(data, streamEnd));
    }

    void file(byte[] preamble, FileChannel channel, Runnable onDone) {
        enqueue(new File(preamble, channel, onDone));
    }

    void rdb(byte[] prefix, byte[] payload, byte[] suffix, CompletableFuture<Void> done) {
        enqueue(new Rdb(prefix, payload, suffix, done));
    }

    void closeAfterWrites() {
        enqueue(new CloseAfter());
    }

    /** 즉시 멈춤 - 남은 것 버림 */
    void stop() {
        synchronized (this) {
            stopped = true;
        }
        thread.interrupt();
        failPending();
    }

    private void enqueue(Item item) {
        synchronized (this) {
            if (!stopped) {
                queue.add(item);
                return;
            }
        }
        fail(item);
    }

    private void run() {
        try {
            while (true) {
                Item item = queue.take();
                writeStartedAt = Math.max(1, clock.getAsLong());
                try {
                    if (!write(item)) {
                        return;
                    }
                } finally {
                    writeStartedAt = 0;
                }
            }
        } catch (InterruptedException e) {
            // stop()
        } catch (IOException e) {
            closeSocket.run();
            onFailure.run();
        } finally {
            synchronized (this) {
                stopped = true;
            }
            failPending();
        }
    }

    /** @return 계속 쓸지 */
    private boolean write(Item item) throws IOException {
        switch (item) {
            case Bytes bytes -> {
                out.write(bytes.data());
                out.flush();
                if (bytes.streamEnd() >= 0) {
                    written.set(bytes.streamEnd());
                }
            }
            case File file -> {
                try (FileChannel channel = file.channel()) {
                    out.write(file.preamble());
                    ByteBuffer buffer = ByteBuffer.allocate(16 * 1024);
                    while (channel.read(buffer.clear()) > 0) {
                        out.write(buffer.array(), 0, buffer.position());
                    }
                    out.flush();
                }
                file.onDone().run();
            }
            case Rdb rdb -> {
                try {
                    out.write(rdb.prefix());
                    out.write(rdb.payload());
                    out.write(rdb.suffix());
                    out.flush();
                } catch (IOException e) {
                    rdb.done().completeExceptionally(e);
                    throw e;
                }
                rdb.done().complete(null);
            }
            case CloseAfter ignored -> {
                out.flush();
                closeSocket.run();
                return false;
            }
        }
        return true;
    }

    private void failPending() {
        List<Item> left = new ArrayList<>();
        queue.drainTo(left);
        left.forEach(ReplicaWriter::fail);
    }

    private static void fail(Item item) {
        switch (item) {
            case Rdb rdb -> rdb.done().completeExceptionally(new IOException("레플리카 연결 끊김"));
            case File file -> {
                try {
                    file.channel().close();
                } catch (IOException ignored) {
                    // 닫기 실패 무시
                }
            }
            default -> {
            }
        }
    }
}
