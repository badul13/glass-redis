package glassredis.persistence;

import glassredis.store.Keyspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * RDB 스냅샷 저장 - rdb.c rdbSave
 * 실행 스레드 전용 - SAVE는 직렬화부터 디스크 확정까지 실행 스레드에서, 그동안 명령 대기 (Redis와 동일)
 */
public final class Snapshot {

    /**
     * @param dir      작업 폴더 (dir 설정)
     * @param filename dbfilename - 기본 dump.rdb
     */
    public record Config(Path dir, String filename) {

        public static Config defaults(Path dir) {
            return new Config(dir, "dump.rdb");
        }
    }

    private final Config config;
    private final Path file;

    public Snapshot(Config config) {
        this.config = Objects.requireNonNull(config, "config");
        this.file = config.dir().resolve(config.filename());
    }

    public Config config() {
        return config;
    }

    public Path file() {
        return file;
    }

    /**
     * SAVE - 임시 파일에 쓰고 fsync 후 이름 바꾸기 (rdbSave)
     * 실패 시 임시 파일 삭제, 기존 dump.rdb 유지
     *
     * @return 실패 사유 - 성공이면 null
     */
    public String save(Keyspace keyspace) {
        long now = System.currentTimeMillis() / 1000;
        Runtime runtime = Runtime.getRuntime();
        byte[] content = RdbWriter.write(keyspace, now, runtime.totalMemory() - runtime.freeMemory());
        Path temp = config.dir().resolve("temp-" + ProcessHandle.current().pid() + ".rdb");
        try {
            Files.createDirectories(config.dir());
            Files.write(temp, content);
            Fsync.file(temp);
            Fsync.replace(temp, file);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // 다음 저장이 덮어씀
            }
            return "Write error while saving DB to the disk: " + e.getMessage();
        }
        return null;
    }
}
