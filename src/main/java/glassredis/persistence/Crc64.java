package glassredis.persistence;

/**
 * RDB 끝 8바이트 체크섬 - Redis crc64.c (Jones 다항식)
 * 반사 입력·출력, 초기값 0, 마지막 XOR 없음 - "123456789" → 0xe9c6d914c4b8d9ca
 * 바이트 하나씩 표 조회 - Redis는 8바이트씩(crcspeed), 결과는 동일
 */
final class Crc64 {

    /** 0xad93d23594c935a9 의 비트 반전 */
    private static final long POLY_REFLECTED = 0x95ac9329ac4bc9b5L;

    private static final long[] TABLE = new long[256];

    static {
        for (int n = 0; n < 256; n++) {
            long crc = n;
            for (int k = 0; k < 8; k++) {
                crc = (crc & 1) != 0 ? (crc >>> 1) ^ POLY_REFLECTED : crc >>> 1;
            }
            TABLE[n] = crc;
        }
    }

    private Crc64() {
    }

    /** crc64(crc, s, l) - 이어서 계산하려면 앞 결과를 crc로 */
    static long update(long crc, byte[] data, int offset, int length) {
        for (int i = offset; i < offset + length; i++) {
            crc = TABLE[(int) ((crc ^ data[i]) & 0xFF)] ^ (crc >>> 8);
        }
        return crc;
    }
}
