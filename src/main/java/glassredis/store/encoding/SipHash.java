package glassredis.store.encoding;

import java.security.SecureRandom;

/**
 * dict용 SipHash-1-2 - Redis 7.2 siphash.c
 * 해시 플러딩 방지용 시작 시 무작위 씨앗 → 재시작마다 순회 순서 변동
 */
public final class SipHash {

    /** 프로세스당 1회 생성 */
    private static final byte[] SEED = new byte[16];

    static {
        new SecureRandom().nextBytes(SEED);
    }

    private SipHash() {
    }

    public static long hash(byte[] in) {
        return hash(in, SEED);
    }

    static long hash(byte[] in, byte[] k) {
        long v0 = 0x736f6d6570736575L;
        long v1 = 0x646f72616e646f6dL;
        long v2 = 0x6c7967656e657261L;
        long v3 = 0x7465646279746573L;
        long k0 = le64(k, 0);
        long k1 = le64(k, 8);
        int len = in.length;
        int end = len - (len % 8);
        long b = ((long) len) << 56;
        v3 ^= k1;
        v2 ^= k0;
        v1 ^= k1;
        v0 ^= k0;

        long[] v = {v0, v1, v2, v3};
        for (int i = 0; i < end; i += 8) {
            long m = le64(in, i);
            v[3] ^= m;
            round(v); // 블록당 1라운드
            v[0] ^= m;
        }
        for (int i = len - 1; i >= end; i--) {
            b |= (long) (in[i] & 0xFF) << (8 * (i - end));
        }
        v[3] ^= b;
        round(v);
        v[0] ^= b;
        v[2] ^= 0xff;
        round(v); // 마무리 2라운드
        round(v);
        return v[0] ^ v[1] ^ v[2] ^ v[3];
    }

    private static void round(long[] v) {
        v[0] += v[1];
        v[1] = Long.rotateLeft(v[1], 13);
        v[1] ^= v[0];
        v[0] = Long.rotateLeft(v[0], 32);
        v[2] += v[3];
        v[3] = Long.rotateLeft(v[3], 16);
        v[3] ^= v[2];
        v[0] += v[3];
        v[3] = Long.rotateLeft(v[3], 21);
        v[3] ^= v[0];
        v[2] += v[1];
        v[1] = Long.rotateLeft(v[1], 17);
        v[1] ^= v[2];
        v[2] = Long.rotateLeft(v[2], 32);
    }

    private static long le64(byte[] p, int at) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v |= (long) (p[at + i] & 0xFF) << (8 * i);
        }
        return v;
    }
}
