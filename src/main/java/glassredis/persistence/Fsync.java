package glassredis.persistence;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** 디스크 확정 도우미 - redis_fsync, rename */
final class Fsync {

    private Fsync() {
    }

    /** 내용을 디스크까지 확정 */
    static void file(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    /**
     * 이름 바꾸기로 교체 - 읽는 쪽은 옛 파일 또는 새 파일만 봄
     * 폴더 fsync(fsyncFileDir) 생략 - 윈도우는 폴더 핸들 fsync 불가
     */
    static void replace(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
