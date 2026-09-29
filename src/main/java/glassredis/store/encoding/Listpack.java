package glassredis.store.encoding;

import java.util.Arrays;

/**
 * listpack — 원소 여러 개를 바이트 배열 하나에 빈틈없이 이어 붙인 구조. Redis 7.2 {@code listpack.c} 를 옮겼다.
 *
 * <p>작은 List, Hash, Set, Sorted Set 은 전부 이 안에 들어간다. 원소 하나마다 객체를 만들고 포인터로 잇는
 * 대신 바이트 배열 하나에 몰아넣으니, 원소가 수십 개일 때는 메모리가 몇 분의 일로 줄고 CPU 캐시에도 잘 맞는다.
 * 대신 가운데에 넣거나 빼면 뒤쪽 바이트를 전부 밀어야 하고, 찾을 때는 처음부터 훑어야 한다.
 * 그래서 Redis 는 원소가 적을 때만 이걸 쓰고, 기준을 넘으면 다른 구조로 바꾼다.
 *
 * <h2>바이트 배치</h2>
 * <pre>
 *   [총 바이트 수 4B][원소 수 2B][원소][원소]...[원소][0xFF]
 *
 *   원소 하나 = [인코딩 + 데이터][backlen]
 * </pre>
 * <ul>
 *   <li>숫자는 전부 리틀 엔디언이다.</li>
 *   <li>원소 수가 65535 이상이면 헤더에 65535(모름)를 적고, 필요할 때 끝까지 세어 본다.</li>
 *   <li><b>backlen</b> 은 "인코딩 + 데이터" 부분의 길이를 뒤에서부터 읽을 수 있게 적은 것이다.
 *       이게 있어서 끝에서 앞으로도 걸을 수 있다. 1~5바이트이고, 7비트씩 끊어 적으며
 *       최상위 비트가 1 이면 "앞 바이트에 이어짐"이다.</li>
 * </ul>
 *
 * <h2>원소 인코딩 (첫 바이트로 구분)</h2>
 * <pre>
 *   0xxxxxxx                     0~127 정수              (1B)
 *   10xxxxxx + 데이터            길이 63 이하 문자열     (1B + 길이)
 *   110xxxxx yyyyyyyy            13비트 정수             (2B)
 *   1110xxxx yyyyyyyy + 데이터   길이 4095 이하 문자열   (2B + 길이)
 *   11110001 + 2B                16비트 정수
 *   11110010 + 3B                24비트 정수
 *   11110011 + 4B                32비트 정수
 *   11110100 + 8B                64비트 정수
 *   11110000 + 4B + 데이터       그보다 긴 문자열
 *   11111111                     끝(EOF)
 * </pre>
 * 문자열로 넣어도 {@code "123"} 처럼 정수로 되돌릴 수 있는 모양이면 정수로 담는다. {@code "007"} 은 문자열로 남는다 —
 * 정수로 담으면 꺼낼 때 {@code "7"} 이 되어 원래 바이트를 잃는다.
 *
 * <h2>C 와 다른 점</h2>
 * C 판은 원소를 가리키는 포인터로 다루고, 고칠 때마다 {@code realloc} 으로 배열을 늘리거나 줄인다.
 * 여기서는 포인터 대신 배열 안의 위치(오프셋)를 쓰고, "없음"은 {@code -1} 이다. 배열은 고칠 때마다
 * 딱 맞는 크기로 새로 만든다. 헤더에 적는 총 바이트 수는 C 판과 똑같이 나온다.
 */
public final class Listpack {

    public static final int HEADER_SIZE = 6;
    public static final int NUMELE_UNKNOWN = 65535;
    private static final int EOF = 0xFF;

    /** long 을 10진수로 쓸 때의 최대 길이 + 1. 이보다 긴 문자열은 볼 것도 없이 정수가 아니다. */
    private static final int LONG_STR_SIZE = 21;

    /** 원소 앞에 넣을지, 그 자리를 바꿀지. 뒤에 넣기는 다음 원소 앞에 넣기로 바꿔 처리한다. */
    public enum Where { BEFORE, AFTER, REPLACE }

    private byte[] lp;

    public Listpack() {
        lp = new byte[HEADER_SIZE + 1];
        setTotalBytes(HEADER_SIZE + 1);
        setNumElements(0);
        lp[HEADER_SIZE] = (byte) EOF;
    }

    // --- 헤더 ---

    /** 헤더에 적힌 총 바이트 수. Redis 가 인코딩을 바꿀지 판단할 때 보는 값이 이것이다. */
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

    /** 원소 수. 헤더에 적혀 있으면 바로, 65535 이상이라 "모름"이면 끝까지 센다. */
    public int length() {
        int count = numElements();
        if (count != NUMELE_UNKNOWN) {
            return count;
        }
        count = 0;
        for (int p = first(); p != -1; p = next(p)) {
            count++;
        }
        // 다시 세어 보니 헤더에 적을 수 있는 범위면 적어둔다. C 판도 그렇게 한다.
        if (count < NUMELE_UNKNOWN) {
            setNumElements(count);
        }
        return count;
    }

    /** 대시보드가 바이트를 그대로 보여줄 때 쓴다. 고치면 안 된다. */
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

    /** 오른쪽 원소. 마지막이었으면 -1. */
    public int next(int p) {
        int q = skip(p);
        return (lp[q] & 0xFF) == EOF ? -1 : q;
    }

    /** 왼쪽 원소. 처음이었으면 -1. 바로 앞 원소의 backlen 을 거꾸로 읽어서 간다. */
    public int prev(int p) {
        if (p == HEADER_SIZE) {
            return -1;
        }
        int q = p - 1;
        long prevLen = decodeBacklen(q);
        prevLen += encodeBacklen(null, 0, prevLen);
        return (int) (q - (prevLen - 1));
    }

    /** 원소 하나를 건너뛴 위치. EOF 일 수 있다. */
    private int skip(int p) {
        long entryLen = currentEncodedSize(p);
        entryLen += encodeBacklen(null, 0, entryLen);
        return (int) (p + entryLen);
    }

    /**
     * index 번째 원소. 음수면 뒤에서부터. 범위 밖이면 -1.
     * 절반을 넘는 위치는 뒤에서부터 걷는다 — 이게 backlen 이 있는 이유다.
     */
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

    /** 이 원소가 정수 인코딩인지. */
    public boolean isInteger(int p) {
        int b = lp[p] & 0xFF;
        return (b & 0x80) == 0 || (b & 0xE0) == 0xC0 || (b >= 0xF1 && b <= 0xF4);
    }

    /** 정수 인코딩 원소의 값. {@link #isInteger} 가 참일 때만 부른다. */
    public long integer(int p) {
        int b = lp[p] & 0xFF;
        long uval;
        long negStart;
        long negMax;
        if ((b & 0x80) == 0) {
            return b & 0x7F; // 7비트 정수는 늘 양수다
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
            return le(p + 1, 8); // 64비트는 자바 long 과 표현이 같다
        } else {
            throw new IllegalStateException("정수 원소가 아닙니다: 0x" + Integer.toHexString(b));
        }
        // 2의 보수. negStart 이상이면 음수다.
        return uval >= negStart ? -(negMax - uval) - 1 : uval;
    }

    /** 원소를 문자열 바이트로. 정수 원소는 10진수 문자열로 바꿔 준다(C 판의 intbuf 경로). */
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

    /** 원소가 주어진 바이트와 같은지. 정수 원소는 상대를 정수로 해석해서 비교한다(lpCompare). */
    public boolean equalsAt(int p, byte[] s) {
        if (isInteger(p)) {
            Long value = toInt64(s);
            return value != null && value == integer(p);
        }
        return Arrays.equals(get(p), s);
    }

    /**
     * p 부터 오른쪽으로 s 와 같은 원소를 찾는다. 비교 사이에 {@code skip} 개씩 건너뛴다 —
     * Hash 는 [필드, 값, 필드, 값] 순이라 skip 1 로 필드만 본다. 없으면 -1.
     */
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

    /** p 에 있는 원소를 지우고, 그 오른쪽 원소의 위치를 준다. 마지막이었으면 -1. */
    public int delete(int p) {
        return insert(null, p, Where.REPLACE);
    }

    public int replace(int p, byte[] s) {
        return insert(s, p, Where.REPLACE);
    }

    /**
     * 넣기, 바꾸기, 지우기를 모두 이 하나로 한다(lpInsert). {@code s} 가 {@code null} 이면 지우기다.
     *
     * <p>하는 일은 결국 바이트 배열 가운데에 구멍을 내거나 메우는 것이다. 새 원소의 크기를 먼저 계산하고,
     * 그만큼 뒤쪽 바이트를 밀어낸 뒤 그 자리에 쓴다.
     *
     * @return 넣었거나 바꾼 원소의 위치. 지웠을 때는 그 오른쪽 원소의 위치(없으면 -1).
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

    /** index 부터 num 개를 지운다(lpDeleteRange). */
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
            // 끝까지 지우는 경우: 그 자리에 EOF 를 찍고 잘라내면 끝이다.
            lp = Arrays.copyOf(lp, p + 1);
            lp[p] = (byte) EOF;
            setTotalBytes(p + 1);
            setNumElements((int) index);
            return;
        }
        deleteRangeWithEntry(p, num);
    }

    /** p 부터 num 개를 지우고, 그 자리(다음 원소)의 위치를 준다. 끝까지 지웠으면 -1. */
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

    /** 원소 하나를 "인코딩 + 데이터" 바이트로 만든다(lpEncodeGetType + lpEncodeString). */
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

    /** 정수를 가장 짧은 정수 인코딩으로(lpEncodeIntegerGetType). */
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

    /** 이 바이트들을 넣으면 listpack 에서 몇 바이트를 차지할지(인코딩 + 데이터 + backlen). */
    public static int entrySize(byte[] s) {
        int encLen = encode(s).length;
        return encLen + (int) encodeBacklen(null, 0, encLen);
    }

    /**
     * backlen 을 쓴다. {@code buf} 가 {@code null} 이면 필요한 바이트 수만 센다.
     * 앞쪽 바이트일수록 상위 비트이고, 첫 바이트를 뺀 나머지는 최상위 비트를 1 로 세운다.
     * 뒤에서부터 읽다가 최상위 비트가 0 인 바이트를 만나면 거기가 시작이다.
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

    /** p 는 backlen 의 마지막 바이트. 거기서 왼쪽으로 읽는다. */
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

    /** 원소의 "인코딩 + 데이터" 길이(backlen 제외). */
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
     * 되돌렸을 때 원래 바이트와 똑같아지는 표기만 정수로 본다(lpStringToInt64 = string2ll).
     * {@code "007"}, {@code "+1"}, {@code "-0"}, {@code " 1"} 은 정수가 아니다. 아니면 {@code null}.
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
        // 부호 없는 64비트로 쌓는다. 자바에는 unsigned long 이 없어서 Long 의 unsigned 연산을 쓴다.
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
