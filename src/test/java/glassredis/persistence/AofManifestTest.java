package glassredis.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AofManifestTest {

    @Test
    @DisplayName("처음 기동 - base 1, incr 1 (redis:7 이 만드는 manifest 와 같은 글자)")
    void firstStart() {
        AofManifest manifest = new AofManifest();
        manifest.newBase(false);
        manifest.lastIncr();
        assertEquals("file appendonly.aof.1.base.aof seq 1 type b\n"
                + "file appendonly.aof.1.incr.aof seq 1 type i\n", manifest.format());
    }

    @Test
    @DisplayName("재작성 - 새 incr 열고, 끝나면 새 base, 옛 base·incr 는 history 로")
    void rewriteCycle() {
        AofManifest manifest = new AofManifest();
        manifest.newBase(false);
        manifest.lastIncr();
        manifest.newIncr(); // 재작성 시작 - 이후 쓰기는 2번으로
        manifest.newBase(false); // 재작성 끝
        manifest.markRewrittenIncrAsHistory(true);
        assertEquals("file appendonly.aof.2.base.aof seq 2 type b\n"
                + "file appendonly.aof.1.incr.aof seq 1 type h\n"
                + "file appendonly.aof.1.base.aof seq 1 type h\n"
                + "file appendonly.aof.2.incr.aof seq 2 type i\n", manifest.format());
        manifest.clearHistory();
        assertEquals(List.of(new AofManifest.FileInfo("appendonly.aof.2.incr.aof", 2, 'i')), manifest.incr());
    }

    @Test
    @DisplayName("AOF 꺼진 채 재작성 - incr 전부 history, base 만 남음")
    void rewriteWhileDisabled() {
        AofManifest manifest = new AofManifest();
        manifest.newBase(false);
        manifest.lastIncr();
        manifest.newBase(false);
        manifest.markRewrittenIncrAsHistory(false);
        assertTrue(manifest.incr().isEmpty());
        assertEquals(2, manifest.history().size());
    }

    @Test
    @DisplayName("읽기·쓰기 왕복 - seq 이어서 증가")
    void roundTrip(@TempDir Path dir) throws IOException {
        AofManifest manifest = new AofManifest();
        manifest.newBase(false);
        manifest.lastIncr();
        manifest.newIncr();
        manifest.persist(dir);

        AofManifest loaded = AofManifest.load(dir);
        assertEquals(manifest.format(), loaded.format());
        assertEquals("appendonly.aof.3.incr.aof", loaded.newIncr());
        assertEquals("appendonly.aof.2.base.aof", loaded.newBase(false));
        assertTrue(Files.notExists(dir.resolve("temp-" + AofManifest.MANIFEST_NAME)), "임시 파일은 이름 바꾸기로 사라짐");
    }

    @Test
    @DisplayName("주석 줄 무시, 모르는 키는 건너뜀")
    void commentsAndUnknownKeys() throws IOException {
        AofManifest manifest = AofManifest.parse("# 주석\nfile a.aof seq 3 type i extra 1\n");
        assertEquals(List.of(new AofManifest.FileInfo("a.aof", 3, 'i')), manifest.incr());
    }

    @Test
    @DisplayName("형식 오류는 기동 중단 - 빈 파일, base 둘, seq 역행, 경로, 모르는 종류")
    void invalid() {
        assertThrows(IOException.class, () -> AofManifest.parse(""));
        assertThrows(IOException.class, () -> AofManifest.parse("file a seq 1 type b\nfile b seq 2 type b\n"));
        assertThrows(IOException.class, () -> AofManifest.parse("file a seq 2 type i\nfile b seq 2 type i\n"));
        assertThrows(IOException.class, () -> AofManifest.parse("file ../a seq 1 type i\n"));
        assertThrows(IOException.class, () -> AofManifest.parse("file a seq 1 type x\n"));
        assertThrows(IOException.class, () -> AofManifest.parse("file a seq 1\n"));
        assertThrows(IOException.class, () -> AofManifest.parse("\n"));
    }
}
