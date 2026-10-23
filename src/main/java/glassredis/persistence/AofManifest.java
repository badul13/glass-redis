package glassredis.persistence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * AOF 파일 목록 - Redis 7 multi part AOF의 appendonly.aof.manifest
 * 한 줄에 파일 하나 - "file 이름 seq 번호 type b|h|i"
 *
 * <pre>
 *   base (b)    재작성 결과, 하나 이하
 *   history (h) 재작성으로 밀려나 지울 파일
 *   incr (i)    base 이후 쌓인 명령, 마지막이 지금 쓰는 파일
 * </pre>
 *
 * <p>복구 순서 - base, incr를 seq 순으로
 * <p>변경은 복사본에 하고 디스크 기록 성공 후 교체 - aofManifestDup
 */
public final class AofManifest {

    /** appendfilename 기본값 */
    public static final String FILENAME = "appendonly.aof";

    public static final String MANIFEST_NAME = FILENAME + ".manifest";

    /** 파일 하나 - type은 'b', 'h', 'i' */
    public record FileInfo(String name, long seq, char type) {

        String format() {
            return "file " + name + " seq " + seq + " type " + type + "\n";
        }

        FileInfo withType(char newType) {
            return new FileInfo(name, seq, newType);
        }
    }

    private FileInfo base;
    private final List<FileInfo> history = new ArrayList<>();
    private final List<FileInfo> incr = new ArrayList<>();
    private long currentBaseSeq;
    private long currentIncrSeq;

    public AofManifest copy() {
        AofManifest copy = new AofManifest();
        copy.base = base;
        copy.history.addAll(history);
        copy.incr.addAll(incr);
        copy.currentBaseSeq = currentBaseSeq;
        copy.currentIncrSeq = currentIncrSeq;
        return copy;
    }

    public FileInfo base() {
        return base;
    }

    public List<FileInfo> history() {
        return List.copyOf(history);
    }

    public List<FileInfo> incr() {
        return List.copyOf(incr);
    }

    public boolean isEmpty() {
        return base == null && incr.isEmpty();
    }

    /**
     * 기존 base는 history로 - getNewBaseFileNameAndMarkPreAsHistory
     *
     * @param rdb aof-use-rdb-preamble - 확장자 .rdb, 아니면 .aof
     */
    public String newBase(boolean rdb) {
        if (base != null) {
            history.addFirst(base.withType('h'));
        }
        String suffix = rdb ? ".rdb" : ".aof";
        base = new FileInfo(FILENAME + "." + (++currentBaseSeq) + ".base" + suffix, currentBaseSeq, 'b');
        return base.name();
    }

    /** getNewIncrAofName */
    public String newIncr() {
        FileInfo info = new FileInfo(FILENAME + "." + (++currentIncrSeq) + ".incr.aof", currentIncrSeq, 'i');
        incr.add(info);
        return info.name();
    }

    /** getLastIncrAofName - 없으면 새로 */
    public String lastIncr() {
        return incr.isEmpty() ? newIncr() : incr.getLast().name();
    }

    /**
     * 재작성 전에 쌓인 incr를 history로 - markRewrittenIncrAofAsHistory
     *
     * @param keepLast AOF 켜짐 - 마지막 파일은 지금 쓰는 중이라 유지
     */
    public void markRewrittenIncrAsHistory(boolean keepLast) {
        int keep = keepLast && !incr.isEmpty() ? 1 : 0;
        while (incr.size() > keep) {
            history.addFirst(incr.removeFirst().withType('h'));
        }
    }

    /** aofDelHistoryFiles 후 */
    public void clearHistory() {
        history.clear();
    }

    /** base, history, incr 순 - getAofManifestAsString */
    public String format() {
        StringBuilder out = new StringBuilder();
        if (base != null) {
            out.append(base.format());
        }
        history.forEach(info -> out.append(info.format()));
        incr.forEach(info -> out.append(info.format()));
        return out.toString();
    }

    /** 임시 파일에 쓰고 fsync 후 이름 바꾸기 - writeAofManifestFile */
    public void persist(Path dir) throws IOException {
        Path temp = dir.resolve("temp-" + MANIFEST_NAME);
        Files.write(temp, format().getBytes(StandardCharsets.UTF_8));
        Fsync.file(temp);
        Fsync.replace(temp, dir.resolve(MANIFEST_NAME));
    }

    /** 없으면 빈 목록 - aofLoadManifestFromDisk */
    public static AofManifest load(Path dir) throws IOException {
        Path file = dir.resolve(MANIFEST_NAME);
        if (!Files.exists(file)) {
            return new AofManifest();
        }
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    /** 형식 오류는 기동 중단 - aofLoadManifestFromFile */
    static AofManifest parse(String text) throws IOException {
        AofManifest manifest = new AofManifest();
        String[] lines = text.split("\n", -1);
        long maxSeq = 0;
        int lineCount = 0;
        for (int i = 0; i < lines.length; i++) {
            // 마지막 개행 뒤 빈 조각
            if (i == lines.length - 1 && lines[i].isEmpty()) {
                break;
            }
            lineCount++;
            String raw = lines[i];
            if (raw.startsWith("#")) {
                continue;
            }
            String line = raw.strip();
            String[] tokens = line.isEmpty() ? new String[0] : line.split("[ \t]+");
            if (tokens.length < 6 || tokens.length % 2 != 0) {
                throw invalid(i + 1, raw, "형식이 맞지 않습니다");
            }
            String name = null;
            long seq = 0;
            char type = 0;
            for (int t = 0; t < tokens.length; t += 2) {
                switch (tokens[t].toLowerCase(Locale.ROOT)) {
                    case "file" -> name = tokens[t + 1];
                    case "seq" -> seq = parseSeq(tokens[t + 1]);
                    case "type" -> type = tokens[t + 1].charAt(0);
                    default -> {
                        // 앞으로 늘어날 키 - 무시
                    }
                }
            }
            if (name == null || seq == 0 || type == 0) {
                throw invalid(i + 1, raw, "file, seq, type 중 빠진 것이 있습니다");
            }
            if (name.contains("/") || name.contains("\\")) {
                throw invalid(i + 1, raw, "파일 이름에 경로를 쓸 수 없습니다");
            }
            FileInfo info = new FileInfo(name, seq, type);
            switch (type) {
                case 'b' -> {
                    if (manifest.base != null) {
                        throw invalid(i + 1, raw, "base 파일이 두 개입니다");
                    }
                    manifest.base = info;
                    manifest.currentBaseSeq = seq;
                }
                case 'h' -> manifest.history.add(info);
                case 'i' -> {
                    if (seq <= maxSeq) {
                        throw invalid(i + 1, raw, "incr 파일 seq 가 커지지 않습니다");
                    }
                    manifest.incr.add(info);
                    manifest.currentIncrSeq = seq;
                    maxSeq = seq;
                }
                default -> throw invalid(i + 1, raw, "알 수 없는 파일 종류입니다");
            }
        }
        if (lineCount == 0) {
            throw new IOException("AOF manifest 파일이 비어 있습니다");
        }
        return manifest;
    }

    /** atoll - 숫자 아니면 0 */
    private static long parseSeq(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static IOException invalid(int line, String text, String reason) {
        return new IOException("AOF manifest " + line + "번째 줄 '" + text + "': " + reason);
    }
}
