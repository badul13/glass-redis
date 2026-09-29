package glassredis.store.encoding;

import java.security.SecureRandom;

/**
 * SipHash-1-2. Redis 가 해시 테이블(dict)의 키를 버킷에 나눌 때 쓰는 해시 함수다. Redis 7.2 {@code siphash.c} 를 옮겼다.
 *
 * <p>자바의 {@code Arrays.hashCode} 를 쓰지 않는 이유는 두 가지다.
 * <ul>
 *   <li><b>씨앗(seed)</b> — 서버가 켜질 때 무작위 128비트 씨앗을 뽑아 섞는다. 공격자가 같은 버킷에 몰리는
 *       키를 미리 계산해서 보내면 해시 테이블이 연결 리스트처럼 느려지는데(해시 플러딩), 씨앗을 모르면 그걸 못 한다.
 *       그래서 같은 키를 넣어도 서버를 켤 때마다 {@code HGETALL} 순서가 달라진다. 실제 Redis 도 그렇다.</li>
 *   <li><b>라운드 수</b> — 표준 SipHash-2-4 보다 라운드를 줄인 1-2 다. Redis 는 속도를 위해 이걸 골랐다.</li>
 * </ul>
 */
public final class SipHash {

    /** 프로세스마다 한 번 뽑는 씨앗. 실제 Redis 도 서버 시작 때 무작위로 정한다. */
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
            round(v); // 블록마다 1라운드 (SipHash-1-2 의 1)
            v[0] ^= m;
        }
        for (int i = len - 1; i >= end; i--) {
            b |= (long) (in[i] & 0xFF) << (8 * (i - end));
        }
        v[3] ^= b;
        round(v);
        v[0] ^= b;
        v[2] ^= 0xff;
        round(v); // 마무리 2라운드 (SipHash-1-2 의 2)
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
