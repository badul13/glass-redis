package glassredis.persistence;

/**
 * RDB 파일 상수 - Redis 7.2 rdb.h
 * <pre>
 *   "REDIS0011"                       매직 + 버전 4자리
 *   [AUX 키 값]...                     redis-ver, ctime 등
 *   [SELECTDB 0][RESIZEDB 키 수 만료 수]
 *   ([EXPIRETIME_MS 8B] 타입 키 값)...  키마다
 *   [EOF][CRC64 8B]                    체크섬은 앞 전체, 리틀 엔디언
 * </pre>
 * 길이 = 첫 바이트 위 2비트로 폭 결정 - 00 6비트, 01 14비트, 10 32·64비트(빅 엔디언), 11 특수 인코딩
 */
final class RdbFormat {

    /** RDB_VERSION - 7.2 */
    static final int VERSION = 11;

    // 길이 머리 (RDB_6BITLEN 등)
    static final int LEN_6BIT = 0;
    static final int LEN_14BIT = 1;
    static final int LEN_32BIT = 0x80;
    static final int LEN_64BIT = 0x81;
    static final int ENCVAL = 3;

    // 특수 인코딩 문자열 (RDB_ENC_*)
    static final int ENC_INT8 = 0;
    static final int ENC_INT16 = 1;
    static final int ENC_INT32 = 2;
    static final int ENC_LZF = 3;

    // 값 타입 (RDB_TYPE_*)
    static final int TYPE_STRING = 0;
    static final int TYPE_LIST = 1;
    static final int TYPE_SET = 2;
    static final int TYPE_ZSET = 3;
    static final int TYPE_HASH = 4;
    static final int TYPE_ZSET_2 = 5;
    static final int TYPE_SET_INTSET = 11;
    static final int TYPE_HASH_LISTPACK = 16;
    static final int TYPE_ZSET_LISTPACK = 17;
    static final int TYPE_LIST_QUICKLIST_2 = 18;
    static final int TYPE_SET_LISTPACK = 20;

    // 특수 opcode (RDB_OPCODE_*)
    static final int OPCODE_FUNCTION2 = 245;
    static final int OPCODE_FUNCTION_PRE_GA = 246;
    static final int OPCODE_MODULE_AUX = 247;
    static final int OPCODE_IDLE = 248;
    static final int OPCODE_FREQ = 249;
    static final int OPCODE_AUX = 250;
    static final int OPCODE_RESIZEDB = 251;
    static final int OPCODE_EXPIRETIME_MS = 252;
    static final int OPCODE_EXPIRETIME = 253;
    static final int OPCODE_SELECTDB = 254;
    static final int OPCODE_EOF = 255;

    /** QUICKLIST_NODE_CONTAINER_PLAIN - 큰 원소 하나짜리 노드 */
    static final int CONTAINER_PLAIN = 1;
    /** QUICKLIST_NODE_CONTAINER_PACKED - listpack 노드 */
    static final int CONTAINER_PACKED = 2;

    /** rdbSaveInfoAuxFields 의 REDIS_VERSION - 대조 기준 버전 */
    static final String REDIS_VERSION = "7.2.4";

    private RdbFormat() {
    }
}
