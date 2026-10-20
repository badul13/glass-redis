package glassredis.command;

/** 키스페이스 밖 서버 동작 - 명령이 직접 못 하는 일 */
public interface ServerControl {

    /** 소켓 없는 테스트용 - 지원 안 함 */
    ServerControl NONE = new ServerControl() {
        @Override
        public String rewriteAppendOnlyFile() {
            return "persistence is not available";
        }

        @Override
        public String save() {
            return "persistence is not available";
        }
    };

    /** BGREWRITEAOF - 실패 사유, 시작했으면 null */
    String rewriteAppendOnlyFile();

    /** SAVE - 실패 사유, 저장했으면 null */
    String save();
}
