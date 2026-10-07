package glassredis.command;

import glassredis.store.Keyspace;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 명령 실행 시 넘기는 서버 상태
 * 명령 객체는 커넥션 간 공유 - 커넥션별 상태(MULTI 등) 추가 시 여기에 필드
 */
public record Context(Keyspace keyspace, Propagation propagation, ServerControl server) {

    public Context(Keyspace keyspace) {
        this(keyspace, new Propagation(), ServerControl.NONE);
    }

    /** server.dirty += count */
    public void dirty(long count) {
        propagation.dirty(count);
    }

    /** server.dirty++ */
    public void dirty() {
        propagation.dirty(1);
    }

    /** rewriteClientCommandVector - 문자열은 ASCII, byte[]는 그대로 */
    public void propagateAs(Object... argv) {
        List<byte[]> bytes = new ArrayList<>(argv.length);
        for (Object arg : argv) {
            bytes.add(arg instanceof byte[] raw ? raw : arg.toString().getBytes(StandardCharsets.US_ASCII));
        }
        propagation.rewrite(bytes);
    }
}
