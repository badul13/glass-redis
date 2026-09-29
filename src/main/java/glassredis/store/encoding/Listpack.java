package glassredis.store.encoding;

import java.util.Arrays;

/**
 * 원소를 바이트 배열 하나에 이어 붙인 구조 - Redis 7.2 listpack.c
 * 포인터 대신 배열 오프셋, 없음은 -1
 * 수정마다 배열을 딱 맞는 크기로 재생성
 * <pre>
 *   [총 바이트 4B][원소 수 2B][원소]...[0xFF]    리틀 엔디언
 *   원소 = [인코딩 + 데이터][backlen]            backlen - 역방향 탐색용 1~5B 길이
 *
 *   0xxxxxxx                     7비트 양의 정수
 *   10xxxxxx + 데이터            63B 이하 문자열
 *   110xxxxx yyyyyyyy            13비트 정수
 *   1110xxxx yyyyyyyy + 데이터   4095B 이하 문자열
 *   11110001 ~ 11110100          16/24/32/64비트 정수
 *   11110000 + 4B + 데이터       긴 문자열
 * </pre>
 * 원소 수 65535 이상이면 헤더에 65535(모름) 기록
 */
public final class Listpack {

    public static final int HEADER_SIZE = 6;
    public static final int NUMELE_UNKNOWN = 65535;
    private static final int EOF = 0xFF;

    /** long 10진수 표기 최대 길이 + 1 */
    private static final int LONG_STR_SIZE = 21;

    public enum Where { BEFORE, AFTER, REPLACE }

    private byte[] lp;

    public Listpack() {
        lp = new byte[HEADER_SIZE + 1];
        setTotalBytes(HEADER_SIZE + 1);
        setNumElements(0);
        lp[HEADER_SIZE] = (byte) EOF;
    }

    // --- 헤더 ---

    /** 헤더의 총 바이트 수 - 인코딩 전환 판단용 */
    public int bytes() {
        return (lp[0] & 0xFF) | (lp[1] & 0xFF) << 8 | (lp[2] & 0xFF) << 16 | (lp[3] & 0xFF) << 24;
    }

    private int numElements() {
        return (lp[4] & 0xFF) | (lp[5] & 0xFF) << 8;
    }

    private void setTotalBytes(int value) {
        lp[0] = (byte) value;
        lp[1] = (byte) (value >>> 8);
        lp[2] = (byte) (value >>> 16);
        lp[3] = (byte) (value >>> 24);
    }

    private void setNumElements(int value) {
        lp[4] = (byte) value;
        lp[5] = (byte) (value >>> 8);
    }

    /** 헤더가 모름(65535)이면 끝까지 계수 */
    public int length() {
        int count = numElements();
        if (count != NUMELE_UNKNOWN) {
            return count;
        }
        count = 0;
        for (int p = first(); p != -1; p = next(p)) {
            count++;
        }
        if (count < NUMELE_UNKNOWN) {
            setNumElements(count);
        }
        return count;
    }

    /** 내부 배열 그대로 - 수정 금지 */
    public byte[] rawBytes() {
        return lp;
    }

    // --- 걷기 ---

    public int first() {
        return (lp[HEADER_SIZE] & 0xFF) == EOF ? -1 : HEADER_SIZE;
    }

    public int last() {
        return prev(bytes() - 1);
    }

    /** 마지막이었으면 -1 */
    public int next(int p) {
        int q = skip(p);
        return (lp[q] & 0xFF) == EOF ? -1 : q;
    }

    /** 처음이었으면 -1 - 앞 원소의 backlen 역방향 판독 */
    public int prev(int p) {
        if (p == HEADER_SIZE) {
            return -1;
        }
        int q = p - 1;
        long prevLen = decodeBacklen(q);
        prevLen += encodeBacklen(null, 0, prevLen);
        return (int) (q - (prevLen - 1));
    }

    /** EOF 위치 반환 가능 */
    private int skip(int p) {
        long entryLen = currentEncodedSize(p);
        entryLen += encodeBacklen(null, 0, entryLen);
        return (int) (p + entryLen);
    }

    /** 음수면 뒤에서부터, 범위 밖이면 -1 - 절반 넘으면 뒤에서 탐색 */
    public int seek(long index) {
        boolean forward = true;
        int count = numElements();
        if (count != NUMELE_UNKNOWN) {
            if (index < 0) {
                index = count + index;
            }
            if (index < 0 || index >= count) {
                return -1;
            }
            if (index > count / 2) {
                forward = false;
                index -= count;
            }
        } else if (index < 0) {
            forward = false;
        }

        if (forward) {
            int p = first();
            while (index > 0 && p != -1) {
                p = next(p);
                index--;
            }
            return p;
        }
        int p = last();
        while (index < -1 && p != -1) {
            p = prev(p);
            index++;
        }
        return p;
    }

    // --- 읽기 ---

    public boolean isInteger(int p) {
        int b = lp[p] & 0xFF;
        return (b & 0x80) == 0 || (b & 0xE0) == 0xC0 || (b >= 0xF1 && b <= 0xF4);
    }

    /** isInteger가 참일 때만 호출 */
    public long integer(int p) {
        int b = lp[p] & 0xFF;
        long uval;
        long negStart;
        long negMax;
        if ((b & 0x80) == 0) {
            return b & 0x7F;
        } else if ((b & 0xE0) == 0xC0) {
            uval = ((long) (b & 0x1F) << 8) | (lp[p + 1] & 0xFF);
            negStart = 1L << 12;
            negMax = 8191;
        } else if (b == 0xF1) {
            uval = le(p + 1, 2);
            negStart = 1L << 15;
            negMax = 0xFFFF;
        } else if (b == 0xF2) {
            uval = le(p + 1, 3);
            negStart = 1L << 23;
            negMax = 0xFFFFFF;
        } else if (b == 0xF3) {
            uval = le(p + 1, 4);
            negStart = 1L << 31;
            negMax = 0xFFFFFFFFL;
        } else if (b == 0xF4) {
            return le(p + 1, 8);
        } else {
            throw new IllegalStateException("정수 원소가 아닙니다: 0x" + Integer.toHexString(b));
        }
        // 2의 보수 - negStart 이상이면 음수
        return uval >= negStart ? -(negMax - uval) - 1 : uval;
    }

    /** 정수 원소는 10진수 문자열로 반환 */
    public byte[] get(int p) {
        if (isInteger(p)) {
            return Long.toString(integer(p)).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        }
        int b = lp[p] & 0xFF;
        int start;
        int length;
        if ((b & 0xC0) == 0x80) {
            length = b & 0x3F;
            start = p + 1;
        } else if ((b & 0xF0) == 0xE0) {
            length = ((b & 0x0F) << 8) | (lp[p + 1] & 0xFF);
            start = p + 2;
        } else {
            length = (int) le(p + 1, 4);
            start = p + 5;
        }
        return Arrays.copyOfRange(lp, start, start + length);
    }

    /** 정수 원소는 s를 정수로 해석해 비교 - lpCompare */
    public boolean equalsAt(int p, byte[] s) {
        if (isInteger(p)) {
            Long value = toInt64(s);
            return value != null && value == integer(p);
        }
        return Arrays.equals(get(p), s);
    }

    /** 비교 사이 skip개씩 건너뜀, 없으면 -1 - Hash는 skip 1로 필드만 비교 */
    public int find(int p, byte[] s, int skip) {
        int skipCount = 0;
        while (p != -1) {
            if (skipCount == 0) {
                if (equalsAt(p, s)) {
                    return p;
                }
                skipCount = skip;
            } else {
                skipCount--;
            }
            p = next(p);
        }
        return -1;
    }

    // --- 고치기 ---

    public void append(byte[] s) {
        insert(s, bytes() - 1, Where.BEFORE);
    }

    public void prepend(byte[] s) {
        int p = first();
        if (p == -1) {
            append(s);
        } else {
            insert(s, p, Where.BEFORE);
        }
    }

    /** 반환값 - 다음 원소 위치, 마지막이었으면 -1 */
    public int delete(int p) {
        return insert(null, p, Where.REPLACE);
    }

    public int replace(int p, byte[] s) {
        return insert(s, p, Where.REPLACE);
    }

    /**
     * 삽입·교체·삭제 공통 - lpInsert, s가 null이면 삭제
     * 반환값 - 삽입·교체한 원소 위치, 삭제 시 다음 원소 위치(없으면 -1)
     */
    public int insert(byte[] s, int p, Where where) {
        boolean delete = s == null;
        if (delete) {
            where = Where.REPLACE;
        }
        if (where == Where.AFTER) {
            p = skip(p);
            where = Where.BEFORE;
        }

        byte[] encoded = delete ? new byte[0] : encode(s);
        int encLen = encoded.length;
        byte[] backlen = new byte[5];
        int backlenSize = delete ? 0 : (int) encodeBacklen(backlen, 0, encLen);

        int oldBytes = bytes();
        int replacedLen = 0;
        if (where == Where.REPLACE) {
            replacedLen = (int) currentEncodedSize(p);
            replacedLen += (int) encodeBacklen(null, 0, replacedLen);
        }
        long newBytes = (long) oldBytes + encLen + backlenSize - replacedLen;
        if (newBytes > 0xFFFFFFFFL || newBytes > Integer.MAX_VALUE) {
            throw new IllegalStateException("listpack 이 너무 커집니다");
        }

        byte[] grown = new byte[(int) newBytes];
        System.arraycopy(lp, 0, grown, 0, p);
        System.arraycopy(lp, p + replacedLen, grown, p + encLen + backlenSize, oldBytes - p - replacedLen);
        if (!delete) {
            System.arraycopy(encoded, 0, grown, p, encLen);
            System.arraycopy(backlen, 0, grown, p + encLen, backlenSize);
        }
        lp = grown;

        if (where != Where.REPLACE || delete) {
            int count = numElements();
            if (count != NUMELE_UNKNOWN) {
                setNumElements(delete ? count - 1 : count + 1);
            }
        }
        setTotalBytes((int) newBytes);

        if (delete) {
            return (lp[p] & 0xFF) == EOF ? -1 : p;
        }
        return p;
    }

    /** lpDeleteRange */
    public void deleteRange(long index, long num) {
        if (num == 0) {
            return;
        }
        int p = seek(index);
        if (p == -1) {
            return;
        }
        int count = numElements();
        if (count != NUMELE_UNKNOWN && index < 0) {
            index = count + index;
        }
        if (count != NUMELE_UNKNOWN && count - index <= num) {
            // 끝까지 삭제 시 그 자리에 EOF 기록 후 절단
            lp = Arrays.copyOf(lp, p + 1);
            lp[p] = (byte) EOF;
            setTotalBytes(p + 1);
            setNumElements((int) index);
            return;
        }
        deleteRangeWithEntry(p, num);
    }

    /** 반환값 - 다음 원소 위치, 끝까지 삭제 시 -1 */
    public int deleteRangeWithEntry(int p, long num) {
        if (num == 0) {
            return p;
        }
        int oldBytes = bytes();
        int tail = p;
        int deleted = 0;
        while (num-- > 0) {
            deleted++;
            tail = skip(tail);
            if ((lp[tail] & 0xFF) == EOF) {
                break;
            }
        }
        byte[] shrunk = new byte[oldBytes - (tail - p)];
        System.arraycopy(lp, 0, shrunk, 0, p);
        System.arraycopy(lp, tail, shrunk, p, oldBytes - tail);
        lp = shrunk;
        setTotalBytes(shrunk.length);
        int count = numElements();
        if (count != NUMELE_UNKNOWN) {
            setNumElements(count - deleted);
        }
        return (lp[p] & 0xFF) == EOF ? -1 : p;
    }

    // --- 인코딩 ---

    /** backlen 제외 인코딩 + 데이터 - lpEncodeGetType, lpEncodeString */
    static byte[] encode(byte[] s) {
        Long value = toInt64(s);
        if (value != null) {
            return encodeInteger(value);
        }
        int len = s.length;
        byte[] out;
        if (len < 64) {
            out = new byte[1 + len];
            out[0] = (byte) (len | 0x80);
            System.arraycopy(s, 0, out, 1, len);
        } else if (len < 4096) {
            out = new byte[2 + len];
            out[0] = (byte) ((len >> 8) | 0xE0);
            out[1] = (byte) len;
            System.arraycopy(s, 0, out, 2, len);
        } else {
            out = new byte[5 + len];
            out[0] = (byte) 0xF0;
            out[1] = (byte) len;
            out[2] = (byte) (len >> 8);
            out[3] = (byte) (len >> 16);
            out[4] = (byte) (len >> 24);
            System.arraycopy(s, 0, out, 5, len);
        }
        return out;
    }

    /** lpEncodeIntegerGetType */
    static byte[] encodeInteger(long v) {
        if (v >= 0 && v <= 127) {
            return new byte[] {(byte) v};
        } else if (v >= -4096 && v <= 4095) {
            long u = v < 0 ? (1L << 13) + v : v;
            return new byte[] {(byte) ((u >> 8) | 0xC0), (byte) u};
        } else if (v >= -32768 && v <= 32767) {
            long u = v < 0 ? (1L << 16) + v : v;
            return new byte[] {(byte) 0xF1, (byte) u, (byte) (u >> 8)};
        } else if (v >= -8388608 && v <= 8388607) {
            long u = v < 0 ? (1L << 24) + v : v;
            return new byte[] {(byte) 0xF2, (byte) u, (byte) (u >> 8), (byte) (u >> 16)};
        } else if (v >= -2147483648L && v <= 2147483647L) {
            long u = v < 0 ? (1L << 32) + v : v;
            return new byte[] {(byte) 0xF3, (byte) u, (byte) (u >> 8), (byte) (u >> 16), (byte) (u >> 24)};
        }
        byte[] out = new byte[9];
        out[0] = (byte) 0xF4;
        for (int i = 0; i < 8; i++) {
            out[1 + i] = (byte) (v >>> (8 * i));
        }
        return out;
    }

    /** backlen 포함 원소 크기 */
    public static int entrySize(byte[] s) {
        int encLen = encode(s).length;
        return encLen + (int) encodeBacklen(null, 0, encLen);
    }

    /**
     * backlen 기록 후 바이트 수 반환 - buf가 null이면 계수만
     * 7비트씩 상위부터 기록, 첫 바이트 외 나머지는 최상위 비트 1
     */
    static long encodeBacklen(byte[] buf, int at, long l) {
        if (l <= 127) {
            if (buf != null) {
                buf[at] = (byte) l;
            }
            return 1;
        } else if (l < 16383) {
            if (buf != null) {
                buf[at] = (byte) (l >> 7);
                buf[at + 1] = (byte) ((l & 127) | 128);
            }
            return 2;
        } else if (l < 2097151) {
            if (buf != null) {
                buf[at] = (byte) (l >> 14);
                buf[at + 1] = (byte) (((l >> 7) & 127) | 128);
                buf[at + 2] = (byte) ((l & 127) | 128);
            }
            return 3;
        } else if (l < 268435455) {
            if (buf != null) {
                buf[at] = (byte) (l >> 21);
                buf[at + 1] = (byte) (((l >> 14) & 127) | 128);
                buf[at + 2] = (byte) (((l >> 7) & 127) | 128);
                buf[at + 3] = (byte) ((l & 127) | 128);
            }
            return 4;
        }
        if (buf != null) {
            buf[at] = (byte) (l >> 28);
            buf[at + 1] = (byte) (((l >> 21) & 127) | 128);
            buf[at + 2] = (byte) (((l >> 14) & 127) | 128);
            buf[at + 3] = (byte) (((l >> 7) & 127) | 128);
            buf[at + 4] = (byte) ((l & 127) | 128);
        }
        return 5;
    }

    /** p는 backlen의 마지막 바이트 - 왼쪽으로 판독 */
    private long decodeBacklen(int p) {
        long val = 0;
        int shift = 0;
        while (true) {
            val |= (long) (lp[p] & 127) << shift;
            if ((lp[p] & 128) == 0) {
                return val;
            }
            shift += 7;
            p--;
            if (shift > 28) {
                throw new IllegalStateException("backlen 이 5바이트를 넘습니다");
            }
        }
    }

    /** backlen 제외 길이 */
    private long currentEncodedSize(int p) {
        int b = lp[p] & 0xFF;
        if ((b & 0x80) == 0) return 1;
        if ((b & 0xC0) == 0x80) return 1 + (b & 0x3F);
        if ((b & 0xE0) == 0xC0) return 2;
        if (b == 0xF1) return 3;
        if (b == 0xF2) return 4;
        if (b == 0xF3) return 5;
        if (b == 0xF4) return 9;
        if ((b & 0xF0) == 0xE0) return 2 + (((b & 0x0F) << 8) | (lp[p + 1] & 0xFF));
        if (b == 0xF0) return 5 + le(p + 1, 4);
        if (b == EOF) return 1;
        throw new IllegalStateException("모르는 인코딩: 0x" + Integer.toHexString(b));
    }

    private long le(int at, int n) {
        long v = 0;
        for (int i = 0; i < n; i++) {
            v |= (long) (lp[at + i] & 0xFF) << (8 * i);
        }
        return v;
    }

    /**
     * 되돌렸을 때 같은 바이트가 되는 표기만 정수 인정 - string2ll, 아니면 null
     * "007", "+1", "-0", " 1"은 정수 아님
     */
    public static Long toInt64(byte[] s) {
        int len = s.length;
        if (len == 0 || len >= LONG_STR_SIZE) {
            return null;
        }
        if (len == 1 && s[0] == '0') {
            return 0L;
        }
        int i = 0;
        boolean negative = false;
        if (s[0] == '-') {
            negative = true;
            i++;
            if (i == len) {
                return null;
            }
        }
        if (s[i] < '1' || s[i] > '9') {
            return null;
        }
        // 2^63까지 담기 위해 부호 없는 64비트로 누적
        long v = s[i++] - '0';
        while (i < len && s[i] >= '0' && s[i] <= '9') {
            if (Long.compareUnsigned(v, Long.divideUnsigned(-1L, 10)) > 0) {
                return null;
            }
            v *= 10;
            int digit = s[i] - '0';
            if (Long.compareUnsigned(v, -1L - digit) > 0) {
                return null;
            }
            v += digit;
            i++;
        }
        if (i < len) {
            return null;
        }
        if (negative) {
            // -(INT64_MIN+1)+1 = 2^63
            if (Long.compareUnsigned(v, Long.MIN_VALUE) > 0) {
                return null;
            }
            return -v;
        }
        if (Long.compareUnsigned(v, Long.MAX_VALUE) > 0) {
            return null;
        }
        return v;
    }
}
