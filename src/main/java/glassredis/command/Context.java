package glassredis.command;

import glassredis.store.Keyspace;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 명령 실행 시 넘기는 서버 상태
 * 명령 객체는 커넥션 간 공유 - 커넥션별 상태는 client()
 */
public final class Context {

    private final Keyspace keyspace;
    private final Propagation propagation;
    private final ServerControl server;
    private final Replication replication;

    /** server.current_client - 실행 스레드가 명령마다 바꿈 */
    private Client client = Client.detached();

    public Context(Keyspace keyspace, Propagation propagation, ServerControl server, Replication replication) {
        this.keyspace = keyspace;
        this.propagation = propagation;
        this.server = server;
        this.replication = replication;
    }

    public Context(Keyspace keyspace, Propagation propagation, ServerControl server) {
        this(keyspace, propagation, server, Replication.NONE);
    }

    public Context(Keyspace keyspace) {
        this(keyspace, new Propagation(), ServerControl.NONE);
    }

    public Keyspace keyspace() {
        return keyspace;
    }

    public Propagation propagation() {
        return propagation;
    }

    public ServerControl server() {
        return server;
    }

    public Replication replication() {
        return replication;
    }

    public Client client() {
        return client;
    }

    public void client(Client current) {
        client = current;
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
