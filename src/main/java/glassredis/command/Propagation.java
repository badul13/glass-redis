package glassredis.command;

import java.util.ArrayList;
import java.util.List;

/**
 * 명령 하나가 AOF로 남길 내용 - server.dirty, rewriteClientCommandVector, alsoPropagate
 * 실행 스레드 전용, 명령마다 begin()으로 초기화
 *
 * <pre>
 *   실행 중 지연 만료 DEL  ─▶ also()    ─┐
 *   명령 자신 (dirty > 0)  ─▶ finish() ─┴─▶ 순서대로 전파, 2개 이상이면 MULTI/EXEC
 * </pre>
 */
public final class Propagation {

    private long dirty;

    /** null - 받은 그대로 전파 */
    private List<byte[]> rewritten;

    private final List<List<byte[]>> pending = new ArrayList<>();

    private boolean active;

    /** 클라이언트가 보낸 이름 포함 전체 인자 */
    private List<byte[]> argv = List.of();

    /** 명령 실행 직전 - argv는 이름 포함 */
    public void begin(List<byte[]> argv) {
        this.argv = argv;
        dirty = 0;
        rewritten = null;
        pending.clear();
        active = true;
    }

    /** 데이터를 실제로 바꾼 횟수 - 0이면 명령 자신은 전파 없음 */
    public void dirty(long count) {
        dirty += count;
    }

    /** 이번 명령의 변경 횟수 - server.dirty 누적용 */
    public long dirtyCount() {
        return dirty;
    }

    /** 이름 포함 전체 인자 - 받은 명령 대신 이 모양으로 전파 */
    public void rewrite(List<byte[]> argv) {
        rewritten = argv;
    }

    /** 명령 실행 중 생긴 추가 전파 - 지연 만료 DEL */
    public void also(List<byte[]> argv) {
        pending.add(argv);
    }

    /** 명령 실행 중 여부 - 밖이면(주기적 만료) 추가 전파는 즉시 단독 */
    public boolean active() {
        return active;
    }

    /** 받은 그대로의 이름 포함 인자 - 일부만 바꿔 전파할 때 */
    public List<byte[]> argv() {
        return argv;
    }

    /** 명령 실행 직후 - 전파할 명령 목록, 순서는 추가 전파 먼저 */
    public List<List<byte[]>> finish() {
        active = false;
        if (dirty > 0) {
            pending.add(rewritten != null ? rewritten : argv);
        }
        List<List<byte[]>> result = List.copyOf(pending);
        pending.clear();
        return result;
    }
}
