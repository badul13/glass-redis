package glassredis.command;

import glassredis.resp.RespValue;

/** 키스페이스 밖 서버 동작 - 명령이 직접 못 하는 일, 응답까지 결정 */
public interface ServerControl {

    /** 소켓 없는 테스트용 - 지원 안 함 */
    ServerControl NONE = new ServerControl() {
        @Override
        public RespValue bgrewriteaof() {
            return new RespValue.Err("ERR persistence is not available");
        }

        @Override
        public RespValue save() {
            return new RespValue.Err("ERR");
        }

        @Override
        public RespValue bgsave(boolean schedule) {
            return new RespValue.Err("ERR");
        }

        @Override
        public long lastSave() {
            return 0;
        }
    };

    /** BGREWRITEAOF - 시작·예약 상태 응답 또는 에러 */
    RespValue bgrewriteaof();

    /** SAVE - OK 또는 에러 */
    RespValue save();

    /** BGSAVE [SCHEDULE] - 시작·예약 상태 응답 또는 에러 */
    RespValue bgsave(boolean schedule);

    /** LASTSAVE - 마지막 저장 성공 시각 (초) */
    long lastSave();
}
