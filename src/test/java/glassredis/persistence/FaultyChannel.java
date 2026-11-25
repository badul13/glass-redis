package glassredis.persistence;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

/**
 * 쓰기 실패 흉내 - 실제 FileChannel 에 위임
 * failAfter >= 0 이면 다음 write 에서 그 바이트까지만 쓰고 IOException (0 이면 한 바이트도 못 씀)
 */
final class FaultyChannel extends FileChannel {

    private final FileChannel real;
    int failAfter = -1;
    boolean failTruncate;
    /** 던질 예외 - 메시지 없는 예외 흉내용으로 교체 가능 */
    IOException failure = new IOException("No space left on device");

    FaultyChannel(FileChannel real) {
        this.real = real;
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
        if (failAfter < 0) {
            return real.write(src);
        }
        int allowed = Math.min(failAfter, src.remaining());
        failAfter -= allowed;
        if (allowed > 0) {
            ByteBuffer part = src.duplicate();
            part.limit(part.position() + allowed);
            int n = real.write(part);
            src.position(src.position() + n);
            return n;
        }
        throw failure;
    }

    @Override
    public FileChannel truncate(long size) throws IOException {
        if (failTruncate) {
            throw new IOException("truncate 실패");
        }
        real.truncate(size);
        return this;
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        return real.read(dst);
    }

    @Override
    public long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
        return real.read(dsts, offset, length);
    }

    @Override
    public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
        return real.write(srcs, offset, length);
    }

    @Override
    public long position() throws IOException {
        return real.position();
    }

    @Override
    public FileChannel position(long newPosition) throws IOException {
        real.position(newPosition);
        return this;
    }

    @Override
    public long size() throws IOException {
        return real.size();
    }

    @Override
    public void force(boolean metaData) throws IOException {
        real.force(metaData);
    }

    @Override
    public long transferTo(long position, long count, WritableByteChannel target) throws IOException {
        return real.transferTo(position, count, target);
    }

    @Override
    public long transferFrom(ReadableByteChannel src, long position, long count) throws IOException {
        return real.transferFrom(src, position, count);
    }

    @Override
    public int read(ByteBuffer dst, long position) throws IOException {
        return real.read(dst, position);
    }

    @Override
    public int write(ByteBuffer src, long position) throws IOException {
        return real.write(src, position);
    }

    @Override
    public MappedByteBuffer map(MapMode mode, long position, long size) throws IOException {
        return real.map(mode, position, size);
    }

    @Override
    public FileLock lock(long position, long size, boolean shared) throws IOException {
        return real.lock(position, size, shared);
    }

    @Override
    public FileLock tryLock(long position, long size, boolean shared) throws IOException {
        return real.tryLock(position, size, shared);
    }

    @Override
    protected void implCloseChannel() throws IOException {
        real.close();
    }
}
