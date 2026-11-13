package glassredis.command;

import glassredis.resp.RespValue;

import java.util.List;

/** 복제 명령이 부르는 서버 쪽 동작 - 응답까지 결정 */
public interface Replication {

    /** 소켓 없는 테스트·AOF 복구용 - 지원 안 함 */
    Replication NONE = new Replication() {
        @Override
        public RespValue replconf(Client client, List<byte[]> args) {
            return new RespValue.Err("ERR replication is not available");
        }

        @Override
        public RespValue sync(Client client, List<byte[]> argv) {
            return new RespValue.Err("ERR replication is not available");
        }

        @Override
        public RespValue role() {
            return new RespValue.Err("ERR replication is not available");
        }

        @Override
        public RespValue replicaof(Client client, List<byte[]> args) {
            return new RespValue.Err("ERR replication is not available");
        }
    };

    /** REPLICAOF <host> <port> | NO ONE - 이름 제외 인자 2개 */
    RespValue replicaof(Client client, List<byte[]> args);

    /** REPLCONF <option> <value> ... - 이름 제외 인자 */
    RespValue replconf(Client client, List<byte[]> args);

    /** SYNC, PSYNC <replid> <offset> - 이름 포함 인자, 성공하면 NO_REPLY (응답은 복제 쪽이 소켓에 직접) */
    RespValue sync(Client client, List<byte[]> argv);

    /** ROLE */
    RespValue role();
}
