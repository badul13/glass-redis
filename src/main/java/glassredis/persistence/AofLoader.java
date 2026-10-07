package glassredis.persistence;

import glassredis.command.Command;
import glassredis.command.CommandRegistry;
import glassredis.command.Context;
import glassredis.store.Keyspace;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 기동 시 복구 - base, incr 순으로 명령 재실행 (loadAppendOnlyFiles)
 * 응답은 버림, 로딩 중에는 만료 판단 중지
 * 명령 중간에서 끝난 파일 - 마지막 완전한 명령 뒤에서 잘라냄, 마지막 파일만 허용 (aof-load-truncated yes)
 * EXEC 없이 끝난 MULTI - MULTI 앞까지 되돌림
 */
public final class AofLoader {

    /** AOF_OK, AOF_NOT_EXIST, AOF_EMPTY, AOF_TRUNCATED, AOF_FAILED */
    public enum Status { OK, NOT_EXIST, EMPTY, TRUNCATED, FAILED }

    /**
     * @param commands 실행한 명령 수 - MULTI·EXEC·SELECT 제외
     * @param truncatedFile 잘라낸 파일 이름 - 없으면 null
     * @param error FAILED 사유 - 아니면 null
     */
    public record Result(Status status, long commands, long bytes, String truncatedFile, long baseBytes,
                         long elapsedMillis, String error) {
    }

    private final Path dir;
    private final CommandRegistry registry;
    private final Context context;

    private long commands;
    private String truncatedFile;
    /** FAILED 사유 */
    private String failure;

    public AofLoader(Path dir, CommandRegistry registry, Keyspace keyspace) {
        this.dir = dir;
        this.registry = registry;
        this.context = new Context(keyspace);
    }

    public Result load(AofManifest manifest) {
        long start = System.currentTimeMillis();
        if (manifest.isEmpty()) {
            return result(Status.NOT_EXIST, 0, 0, start, null);
        }
        List<AofManifest.FileInfo> files = new ArrayList<>();
        if (manifest.base() != null) {
            files.add(manifest.base());
        }
        files.addAll(manifest.incr());

        long total = 0;
        for (AofManifest.FileInfo file : files) {
            Path path = dir.resolve(file.name());
            if (!Files.exists(path)) {
                return result(Status.FAILED, total, 0, start, file.name() + " 파일이 없습니다");
            }
            try {
                total += Files.size(path);
            } catch (IOException e) {
                return result(Status.FAILED, total, 0, start, e.getMessage());
            }
        }
        if (total == 0) {
            return result(Status.EMPTY, 0, 0, start, null);
        }

        context.keyspace().setLoading(true);
        try {
            Status status = Status.OK;
            long baseBytes = 0;
            for (int i = 0; i < files.size(); i++) {
                AofManifest.FileInfo file = files.get(i);
                boolean last = i == files.size() - 1;
                Path path = dir.resolve(file.name());
                if (file.type() == 'b') {
                    baseBytes = Files.size(path);
                }
                Status one = loadFile(path);
                if (one == Status.TRUNCATED && !last) {
                    return result(Status.FAILED, total, baseBytes, start,
                            file.name() + " 이 잘렸는데 마지막 파일이 아닙니다");
                }
                if (one == Status.FAILED) {
                    return result(Status.FAILED, total, baseBytes, start, failure);
                }
                if (one == Status.TRUNCATED) {
                    status = Status.TRUNCATED;
                }
            }
            return result(status, total, baseBytes, start, null);
        } catch (IOException e) {
            return result(Status.FAILED, total, 0, start, e.getMessage());
        } finally {
            context.keyspace().setLoading(false);
        }
    }

    /** loadSingleAppendOnlyFile - 빈 파일은 EMPTY */
    Status loadFile(Path path) throws IOException {
        byte[] data = Files.readAllBytes(path);
        if (data.length == 0) {
            return Status.EMPTY;
        }
        Reader in = new Reader(data);
        long validUpTo = 0;
        long validBeforeMulti = 0;
        List<List<byte[]>> queued = null;

        while (true) {
            int lineStart = in.pos;
            byte[] line = in.line();
            if (line == null) {
                if (in.pos == lineStart) {
                    break; // 정상 끝
                }
                return truncate(path, validUpTo, "명령 중간에서 끝나"); // 줄 끝 개행 없이 끝남
            }
            if (line.length > 0 && line[0] == '#') {
                continue; // 시각 표시 같은 주석
            }
            if (line.length == 0 || line[0] != '*') {
                return fail(path, "형식 오류 - '*' 로 시작하지 않는 줄");
            }
            long argc = Reader.number(line);
            if (argc < 1) {
                return fail(path, "형식 오류 - 인자 수가 1보다 작음");
            }
            List<byte[]> argv = new ArrayList<>((int) Math.min(argc, 1024));
            for (long j = 0; j < argc; j++) {
                byte[] lengthLine = in.line();
                if (lengthLine == null) {
                    return truncate(path, validUpTo, "명령 중간에서 끝나");
                }
                if (lengthLine.length == 0 || lengthLine[0] != '$') {
                    return fail(path, "형식 오류 - '$' 로 시작하지 않는 길이 줄");
                }
                long length = Reader.number(lengthLine);
                byte[] arg = in.bytes(length);
                // 내용 뒤 CRLF 두 바이트 - 값 확인 없음 (Redis와 동일)
                if (arg == null || in.bytes(2) == null) {
                    return truncate(path, validUpTo, "명령 중간에서 끝나");
                }
                argv.add(arg);
            }

            String name = new String(argv.get(0), StandardCharsets.US_ASCII).toUpperCase(Locale.ROOT);
            switch (name) {
                case "MULTI" -> {
                    validBeforeMulti = validUpTo;
                    queued = new ArrayList<>();
                }
                case "EXEC" -> {
                    if (queued != null) {
                        queued.forEach(this::execute);
                        queued = null;
                    }
                }
                case "SELECT" -> {
                    // DB 0 하나뿐
                    if (argv.size() < 2 || !"0".equals(new String(argv.get(1), StandardCharsets.US_ASCII))) {
                        return fail(path, "DB 0 하나만 지원 - SELECT 0 이 아닌 명령");
                    }
                }
                default -> {
                    if (registry.find(name) == null) {
                        return fail(path, "알 수 없는 명령 '" + name + "'");
                    }
                    if (queued != null) {
                        queued.add(argv);
                    } else {
                        execute(argv);
                    }
                }
            }
            validUpTo = in.pos;
        }

        if (queued != null) {
            // EXEC 없이 끝난 트랜잭션 - MULTI 앞까지 되돌림
            return truncate(path, validBeforeMulti, "MULTI 뒤 EXEC 없이 끝나");
        }
        return Status.OK;
    }

    private void execute(List<byte[]> argv) {
        Command command = registry.find(new String(argv.get(0), StandardCharsets.US_ASCII).toUpperCase(Locale.ROOT));
        context.propagation().begin(argv);
        command.execute(context, argv.subList(1, argv.size()));
        context.propagation().finish();
        commands++;
    }

    /** 마지막 완전한 명령 뒤에서 자르기 - 이어서 쓰는 incr 파일도 이 자리부터 */
    /** @param reason 로그 문구 - 끊긴 명령 또는 끝나지 않은 트랜잭션 */
    private Status truncate(Path path, long validUpTo, String reason) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.truncate(validUpTo);
        }
        truncatedFile = path.getFileName().toString();
        System.err.printf("[glass-redis] AOF %s 가 %s %d 바이트 위치에서 잘라냈습니다%n",
                truncatedFile, reason, validUpTo);
        return Status.TRUNCATED;
    }

    private Status fail(Path path, String reason) {
        failure = path.getFileName() + ": " + reason;
        return Status.FAILED;
    }

    private Result result(Status status, long bytes, long baseBytes, long start, String error) {
        return new Result(status, commands, bytes, truncatedFile, baseBytes, System.currentTimeMillis() - start, error);
    }

    /** fgets·fread 흉내 - 모자라면 null */
    private static final class Reader {

        private final byte[] data;
        private int pos;

        Reader(byte[] data) {
            this.data = data;
        }

        /** 개행까지 (개행·CR 제외) - 개행 없이 끝나면 null, pos는 끝으로 */
        byte[] line() {
            int start = pos;
            for (int i = start; i < data.length; i++) {
                if (data[i] == '\n') {
                    pos = i + 1;
                    int end = i > start && data[i - 1] == '\r' ? i - 1 : i;
                    return Arrays.copyOfRange(data, start, end);
                }
            }
            pos = data.length;
            return null;
        }

        byte[] bytes(long length) {
            if (length < 0 || length > data.length - pos) {
                pos = data.length;
                return null;
            }
            byte[] out = Arrays.copyOfRange(data, pos, pos + (int) length);
            pos += (int) length;
            return out;
        }

        /** atoi·strtol - 앞 문자 하나 건너뛰고 숫자 부분만, 없으면 0 */
        static long number(byte[] line) {
            long value = 0;
            int i = 1;
            boolean negative = i < line.length && line[i] == '-';
            if (negative) {
                i++;
            }
            for (; i < line.length && line[i] >= '0' && line[i] <= '9'; i++) {
                value = value * 10 + (line[i] - '0');
            }
            return negative ? -value : value;
        }
    }
}
