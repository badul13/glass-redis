package glassredis.persistence;

import java.util.Arrays;

/**
 * RDB 문자열 압축 - Redis lzf_c.c, lzf_d.c (liblzf 3.6, HLOG 16, VERY_FAST)
 * 포인터 대신 배열 오프셋
 * <pre>
 *   000LLLLL + L+1바이트          리터럴 1~32바이트
 *   LLLooooo oooooooo             앞 데이터 o+1 칸 뒤에서 L+2바이트 복사 (L 1~6)
 *   111ooooo LLLLLLLL oooooooo    같은 방식, L+9바이트
 * </pre>
 * Redis와 차이 - 해시 테이블 0 초기화 (INIT_HTAB 1과 같은 결과)
 * Redis는 INIT_HTAB 0이라 스택에 남은 옛 포인터가 우연히 맞으면 다른 역참조 선택 가능
 * 결과 바이트는 드물게 다를 수 있지만 압축 해제 값은 동일
 */
final class Lzf {

    private static final int HLOG = 16;
    private static final int HSIZE = 1 << HLOG;
    private static final int MAX_LIT = 1 << 5;
    private static final int MAX_OFF = 1 << 13;
    private static final int MAX_REF = (1 << 8) + (1 << 3);

    private Lzf() {
    }

    /**
     * lzf_compress - outLen 안에 못 담으면 null
     *
     * @return 압축 결과, 길이는 실제 압축 크기
     */
    static byte[] compress(byte[] in, int outLen) {
        int inEnd = in.length;
        if (inEnd == 0 || outLen <= 0) {
            return null;
        }
        // 빈 칸 0, 아니면 위치 + 1 - C의 NULL 포인터 대신
        int[] htab = new int[HSIZE];
        // rdbSaveLzfStringObject 의 zmalloc(outlen + 1) - 끝 run 머리 한 칸 여유
        byte[] out = new byte[outLen + 1];
        int outEnd = outLen;
        int ip = 0;
        int op = 0;
        int lit = 0;
        op++; // run 시작

        int hval = first(in, ip);
        while (ip < inEnd - 2) {
            hval = next(hval, in, ip);
            int slot = index(hval);
            int ref = htab[slot] - 1;
            htab[slot] = ip + 1;

            int off = ip - ref - 1;
            // ref > in_data - 첫 바이트는 참조 대상 아님
            if (ref > 0 && off < MAX_OFF
                    && in[ref + 2] == in[ip + 2] && in[ref] == in[ip] && in[ref + 1] == in[ip + 1]) {
                int len = 2;
                int maxlen = inEnd - ip - len;
                if (maxlen > MAX_REF) {
                    maxlen = MAX_REF;
                }
                if (op + 3 + 1 >= outEnd && op - (lit == 0 ? 1 : 0) + 3 + 1 >= outEnd) {
                    return null;
                }
                out[op - lit - 1] = (byte) (lit - 1); // run 끝
                op -= lit == 0 ? 1 : 0; // 빈 run 취소

                match:
                {
                    if (maxlen > 16) {
                        for (int k = 0; k < 16; k++) {
                            len++;
                            if (in[ref + len] != in[ip + len]) {
                                break match;
                            }
                        }
                    }
                    do {
                        len++;
                    } while (len < maxlen && in[ref + len] == in[ip + len]);
                }

                len -= 2; // 이제 길이 - 1
                ip++;
                if (len < 7) {
                    out[op++] = (byte) ((off >> 8) + (len << 5));
                } else {
                    out[op++] = (byte) ((off >> 8) + (7 << 5));
                    out[op++] = (byte) (len - 7);
                }
                out[op++] = (byte) off;
                lit = 0;
                op++; // run 시작

                ip += len + 1;
                if (ip >= inEnd - 2) {
                    break;
                }
                // VERY_FAST - 복사한 구간 끝 두 위치만 해시에 등록
                ip -= 2;
                hval = first(in, ip);
                hval = next(hval, in, ip);
                htab[index(hval)] = ip + 1;
                ip++;
                hval = next(hval, in, ip);
                htab[index(hval)] = ip + 1;
                ip++;
            } else {
                if (op >= outEnd) {
                    return null;
                }
                lit++;
                out[op++] = in[ip++];
                if (lit == MAX_LIT) {
                    out[op - lit - 1] = (byte) (lit - 1);
                    lit = 0;
                    op++;
                }
            }
        }

        if (op + 3 > outEnd) { // 남은 건 최대 3바이트
            return null;
        }
        while (ip < inEnd) {
            lit++;
            out[op++] = in[ip++];
            if (lit == MAX_LIT) {
                out[op - lit - 1] = (byte) (lit - 1);
                lit = 0;
                op++;
            }
        }
        out[op - lit - 1] = (byte) (lit - 1); // run 끝
        op -= lit == 0 ? 1 : 0;
        return Arrays.copyOf(out, op);
    }

    /**
     * lzf_decompress - 결과 길이가 originalLength와 다르면 손상
     *
     * @throws IllegalArgumentException 손상된 입력
     */
    static byte[] decompress(byte[] in, int originalLength) {
        byte[] out = new byte[originalLength];
        int ip = 0;
        int op = 0;
        while (ip < in.length) {
            int ctrl = in[ip++] & 0xFF;
            if (ctrl < MAX_LIT) {
                ctrl++;
                if (op + ctrl > out.length || ip + ctrl > in.length) {
                    throw new IllegalArgumentException("Invalid LZF compressed string");
                }
                System.arraycopy(in, ip, out, op, ctrl);
                ip += ctrl;
                op += ctrl;
            } else {
                int len = ctrl >>> 5;
                int ref = op - ((ctrl & 0x1f) << 8) - 1;
                if (ip >= in.length) {
                    throw new IllegalArgumentException("Invalid LZF compressed string");
                }
                if (len == 7) {
                    len += in[ip++] & 0xFF;
                    if (ip >= in.length) {
                        throw new IllegalArgumentException("Invalid LZF compressed string");
                    }
                }
                ref -= in[ip++] & 0xFF;
                if (op + len + 2 > out.length || ref < 0) {
                    throw new IllegalArgumentException("Invalid LZF compressed string");
                }
                // 겹칠 수 있음 - 한 바이트씩
                for (int i = 0; i < len + 2; i++) {
                    out[op++] = out[ref++];
                }
            }
        }
        if (op != originalLength) {
            throw new IllegalArgumentException("Invalid LZF compressed string");
        }
        return out;
    }

    /** FRST */
    private static int first(byte[] in, int p) {
        return (in[p] & 0xFF) << 8 | (in[p + 1] & 0xFF);
    }

    /** NEXT */
    private static int next(int v, byte[] in, int p) {
        return v << 8 | (in[p + 2] & 0xFF);
    }

    /** IDX - VERY_FAST, 부호 없는 32비트 연산 */
    private static int index(int h) {
        return ((h >>> (3 * 8 - HLOG)) - h * 5) & (HSIZE - 1);
    }
}
