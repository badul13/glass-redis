package glassredis.persistence;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** 디스크 확정 도우미 - redis_fsync, rename */
public final class Fsync {

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
     * 폴더 확정은 directoryOf - Redis 도 rename 뒤 fsyncFileDir 를 따로 부르는 곳만
     */
    static void replace(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().startsWith("windows");

    /**
     * fsyncFileDir - 파일이 든 폴더의 항목(이름 바꾸기·새 파일)을 디스크까지 확정
     * 윈도우 - 폴더를 FileChannel 로 열 수 없어 생략 (정말 못 맞추는 차이, NTFS 는 메타데이터 저널링)
     */
    public static void directoryOf(Path file) throws IOException {
        if (WINDOWS) {
            return;
        }
        Path dir = file.toAbsolutePath().getParent();
        try (FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }
}
