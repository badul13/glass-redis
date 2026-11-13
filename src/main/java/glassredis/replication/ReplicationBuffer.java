package glassredis.replication;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 복제 스트림 공유 버퍼 + 백로그 - feedReplicationBuffer, incrementalTrimReplicationBacklog
 * 블록 하나를 백로그와 레플리카들이 같이 참조, 참조 수가 백로그 1 뿐인 앞 블록만 잘라냄
 * 실행 스레드 전용
 *
 * <pre>
 *   blocks:  [b0][b1][b2][b3 ...used|빈칸]
 *             ▲            ▲
 *        백로그 참조     느린 레플리카 참조 (여기부터 아직 못 보냄)
 * </pre>
 */
final class ReplicationBuffer {

    /** PROTO_REPLY_CHUNK_BYTES - 블록 최소 요청 크기 */
    static final int CHUNK = 16 * 1024;
    /** sizeof(replBufBlock) - refcount, id, repl_offset, size, used */
    static final int BLOCK_HEADER = 40;
    /** sizeof(listNode) */
    static final int LIST_NODE = 24;
    /** REPL_BACKLOG_TRIM_BLOCKS_PER_CALL */
    static final int TRIM_BLOCKS_PER_CALL = 64;

    /** replBufBlock */
    static final class Block {
        final long id;
        /** 첫 바이트의 복제 오프셋 (1부터) */
        final long replOffset;
        final byte[] buf;
        int used;
        int refcount;

        Block(long id, long replOffset, int size) {
            this.id = id;
            this.replOffset = replOffset;
            this.buf = new byte[size];
        }

        int size() {
            return buf.length;
        }
    }

    /** 블록을 참조하는 쪽 - 레플리카 */
    interface Ref {
        /** canFeedReplicaReplBuffer */
        boolean canFeed();

        Block refBlock();

        int refPos();

        void ref(Block block, int pos);
    }

    /** repl_backlog_size */
    private final long backlogSize;
    /** server.slaves - 버퍼를 같이 쓰는 레플리카 */
    private final List<? extends Ref> replicas;
    /** 새 블록이 생겼을 때 - 출력 버퍼 한도 검사 (closeClientOnOutputBufferLimitReached) */
    private final Consumer<Ref> onNewBlock;

    /** server.repl_buffer_blocks */
    private final List<Block> blocks = new ArrayList<>();
    private long nextBlockId;
    /** server.repl_buffer_mem */
    private long memory;

    /** server.master_repl_offset - 백로그 없어도 유지 */
    private long masterReplOffset;

    /** server.repl_backlog != NULL */
    private boolean backlog;
    /** repl_backlog->histlen */
    private long histlen;
    /** repl_backlog->offset - 백로그 첫 바이트의 오프셋 */
    private long backlogOffset;
    /** repl_backlog->ref_repl_buf_node */
    private Block backlogRef;

    ReplicationBuffer(long backlogSize, List<? extends Ref> replicas, Consumer<Ref> onNewBlock) {
        this.backlogSize = backlogSize;
        this.replicas = replicas;
        this.onNewBlock = onNewBlock;
    }

    long masterReplOffset() {
        return masterReplOffset;
    }

    /** 백로그 없는 상태의 전파 - AOF 만 켜져 있을 때 명령마다 1 (replicationFeedSlaves) */
    void advanceWithoutBacklog() {
        masterReplOffset++;
    }

    boolean hasBacklog() {
        return backlog;
    }

    long histlen() {
        return histlen;
    }

    long backlogOffset() {
        return backlogOffset;
    }

    long memory() {
        return memory;
    }

    int blockCount() {
        return blocks.size();
    }

    /** createReplicationBacklog - 첫 바이트는 다음에 만들어질 바이트 */
    void createBacklog() {
        backlog = true;
        histlen = 0;
        backlogRef = null;
        backlogOffset = masterReplOffset + 1;
    }

    /** freeReplicationBacklog - 레플리카가 하나도 없을 때만 */
    void freeBacklog() {
        if (!backlog) {
            return;
        }
        blocks.clear();
        memory = 0;
        backlogRef = null;
        histlen = 0;
        backlog = false;
    }

    /** feedReplicationBuffer - 꼬리 블록 빈칸부터 채우고, 모자라면 새 블록 */
    void feed(byte[] bytes) {
        if (!backlog) {
            return;
        }
        int offset = 0;
        int length = bytes.length;
        while (length > 0) {
            Block startBlock = null;
            int startPos = 0;
            boolean added = false;

            Block tail = blocks.isEmpty() ? null : blocks.getLast();
            if (tail != null && tail.size() > tail.used) {
                startBlock = tail;
                startPos = tail.used;
                int copy = Math.min(tail.size() - tail.used, length);
                System.arraycopy(bytes, offset, tail.buf, tail.used, copy);
                tail.used += copy;
                offset += copy;
                length -= copy;
                masterReplOffset += copy;
                histlen += copy;
            }
            if (length > 0) {
                // 16KB 이상, 백로그의 1/16 이하 - 작으면 이어 붙이기 좋고 크면 잘라내기 어려움
                long limit = Math.max(backlogSize / 16, CHUNK);
                long requested = Math.min(Math.max(length, CHUNK), limit);
                long usable = Jemalloc.usableSize(requested + BLOCK_HEADER);
                Block block = new Block(nextBlockId++, masterReplOffset + 1, (int) (usable - BLOCK_HEADER));
                int copy = Math.min(block.size(), length);
                System.arraycopy(bytes, offset, block.buf, 0, copy);
                block.used = copy;
                blocks.add(block);
                memory += usable + LIST_NODE;
                added = true;
                if (startBlock == null) {
                    startBlock = block;
                    startPos = 0;
                }
                offset += copy;
                length -= copy;
                masterReplOffset += copy;
                histlen += copy;
            }

            for (Ref replica : List.copyOf(replicas)) {
                if (!replica.canFeed()) {
                    continue;
                }
                if (replica.refBlock() == null) {
                    replica.ref(startBlock, startPos);
                    startBlock.refcount++;
                }
                if (added) {
                    onNewBlock.accept(replica);
                }
            }
            if (backlogRef == null) {
                backlogRef = startBlock;
                startBlock.refcount++;
            }
            if (added) {
                trim(TRIM_BLOCKS_PER_CALL);
            }
        }
    }

    /**
     * incrementalTrimReplicationBacklog
     * 백로그만 참조하는 첫 블록을, 잘라도 백로그 크기 이상 남을 때만 제거
     */
    void trim(int maxBlocks) {
        if (!backlog) {
            return;
        }
        int trimmed = 0;
        while (histlen > backlogSize && trimmed < maxBlocks) {
            if (blocks.size() <= 1) {
                break;
            }
            Block first = blocks.getFirst();
            if (first.refcount != 1) {
                break;
            }
            if (histlen - first.size() <= backlogSize) {
                break;
            }
            first.refcount--;
            trimmed++;
            histlen -= first.size();
            Block next = blocks.get(1);
            backlogRef = next;
            next.refcount++;
            memory -= first.size() + LIST_NODE + BLOCK_HEADER;
            blocks.removeFirst();
        }
        backlogOffset = masterReplOffset - histlen + 1;
    }

    /**
     * addReplyReplicationBacklog - offset 부터 보내도록 참조 설정
     *
     * @param offset 레플리카가 원하는 첫 바이트
     * @return 보낼 바이트 수
     */
    long referenceFrom(Ref replica, long offset) {
        if (histlen == 0) {
            return 0;
        }
        long skip = offset - backlogOffset;
        int index = blocks.indexOf(backlogRef);
        Block block = null;
        for (int i = index; i < blocks.size(); i++) {
            Block candidate = blocks.get(i);
            if (candidate.replOffset + candidate.used >= offset) {
                block = candidate;
                break;
            }
        }
        if (block == null) {
            throw new IllegalStateException("백로그에 오프셋 " + offset + " 이 없음");
        }
        block.refcount++;
        replica.ref(block, (int) (offset - block.replOffset));
        return histlen - skip;
    }

    /** copyReplicaOutputBuffer - 같은 BGSAVE 에 붙는 레플리카가 앞 레플리카 위치를 그대로 */
    void copyReference(Ref from, Ref to) {
        if (from.refBlock() == null) {
            return;
        }
        to.ref(from.refBlock(), from.refPos());
        from.refBlock().refcount++;
    }

    /**
     * 쓰기 스레드가 written 까지 보냄 - _writeToClient 의 블록 넘기기
     * 다 보낸 블록 다음이 있으면 참조를 옮기고 백로그 정리
     */
    void advance(Ref replica, long written) {
        while (replica.refBlock() != null) {
            Block block = replica.refBlock();
            long sentInBlock = written - (block.replOffset - 1);
            int pos = (int) Math.max(replica.refPos(), Math.min(block.used, sentInBlock));
            replica.ref(block, pos);
            if (pos != block.used) {
                return;
            }
            Block next = next(block);
            if (next == null) {
                return;
            }
            block.refcount--;
            next.refcount++;
            replica.ref(next, 0);
            trim(TRIM_BLOCKS_PER_CALL);
        }
    }

    /** freeReplicaReferencedReplBuffer */
    void release(Ref replica) {
        if (replica.refBlock() != null) {
            replica.refBlock().refcount--;
            replica.ref(null, 0);
            trim(TRIM_BLOCKS_PER_CALL);
        }
    }

    /** from 다음 바이트부터 끝까지 복사 - 레플리카 쓰기 스레드로 넘길 몫 */
    byte[] copySince(long from) {
        long length = masterReplOffset - from;
        if (length <= 0) {
            return new byte[0];
        }
        byte[] out = new byte[(int) length];
        int written = 0;
        for (Block block : blocks) {
            long blockEnd = block.replOffset - 1 + block.used;
            if (blockEnd <= from) {
                continue;
            }
            int start = (int) Math.max(0, from - (block.replOffset - 1));
            int copy = block.used - start;
            System.arraycopy(block.buf, start, out, written, copy);
            written += copy;
        }
        if (written != length) {
            throw new IllegalStateException("복제 버퍼에 " + from + " 이후 바이트가 모자람");
        }
        return out;
    }

    /** getClientOutputBufferMemoryUsage 의 레플리카 쪽 - 참조 블록부터 꼬리까지 */
    long outputBufferUsage(Ref replica) {
        Block current = replica.refBlock();
        if (current == null) {
            return 0;
        }
        Block last = blocks.getLast();
        long bytes = last.replOffset + last.size() - current.replOffset;
        long nodes = last.id - current.id + 1;
        return bytes + (long) (LIST_NODE + BLOCK_HEADER) * nodes;
    }

    private Block next(Block block) {
        int index = (int) (block.id - blocks.getFirst().id);
        return index + 1 < blocks.size() ? blocks.get(index + 1) : null;
    }
}
