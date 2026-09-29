package glassredis.store.encoding;

/**
 * 정렬된 정수 배열 - Redis 7.2 intset.c
 * 모든 원소 동일 폭(2/4/8바이트) - 넓은 값 유입 시 전체 재작성, 폭 축소 없음
 * 배치 - [폭 4B][원소 수 4B][원소...], 리틀 엔디언
 */
public final class Intset {

    static final int ENC_INT16 = 2;
    static final int ENC_INT32 = 4;
    static final int ENC_INT64 = 8;
    private static final int HEADER = 8;

    private int encoding = ENC_INT16;
    private int length;
    /** 헤더 없이 원소만 */
    private byte[] contents = new byte[0];

    public int length() {
        return length;
    }

    /** 원소 폭(바이트) */
    public int encoding() {
        return encoding;
    }

    /** C 구현 기준 크기 - 헤더 포함 */
    public int bytes() {
        return HEADER + length * encoding;
    }

    public long get(int pos) {
        return getEncoded(pos, encoding);
    }

    public long max() {
        return get(length - 1);
    }

    public long min() {
        return get(0);
    }

    public boolean contains(long value) {
        return valueEncoding(value) <= encoding && search(value) >= 0;
    }

    /** 이미 있었으면 false */
    public boolean add(long value) {
        if (valueEncoding(value) > encoding) {
            // 폭 초과 값은 기존 값 전부보다 크거나 작음 → 양 끝 삽입
            upgradeAndAdd(value);
            return true;
        }
        int found = search(value);
        if (found >= 0) {
            return false;
        }
        int pos = -found - 1;
        resize(length + 1);
        moveTail(pos, pos + 1);
        set(pos, value);
        length++;
        return true;
    }

    /** 폭 축소 없음 */
    public boolean remove(long value) {
        if (valueEncoding(value) > encoding) {
            return false;
        }
        int pos = search(value);
        if (pos < 0) {
            return false;
        }
        moveTail(pos + 1, pos);
        resize(length - 1);
        length--;
        return true;
    }

    /** C 구현 기준 바이트 - 헤더 포함 */
    public byte[] rawBytes() {
        byte[] out = new byte[bytes()];
        writeLe(out, 0, encoding, 4);
        writeLe(out, 4, length, 4);
        System.arraycopy(contents, 0, out, HEADER, length * encoding);
        return out;
    }

    static int valueEncoding(long v) {
        if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
            return ENC_INT64;
        }
        if (v < Short.MIN_VALUE || v > Short.MAX_VALUE) {
            return ENC_INT32;
        }
        return ENC_INT16;
    }

    /** 찾으면 위치, 못 찾으면 -(삽입 위치) - 1 */
    private int search(long value) {
        if (length == 0) {
            return -1;
        }
        if (value > get(length - 1)) {
            return -length - 1;
        }
        if (value < get(0)) {
            return -1;
        }
        int min = 0;
        int max = length - 1;
        while (max >= min) {
            int mid = (min + max) >>> 1;
            long cur = get(mid);
            if (value > cur) {
                min = mid + 1;
            } else if (value < cur) {
                max = mid - 1;
            } else {
                return mid;
            }
        }
        return -min - 1;
    }

    private void upgradeAndAdd(long value) {
        int oldEncoding = encoding;
        boolean prepend = value < 0;
        byte[] old = contents;
        encoding = valueEncoding(value);
        contents = new byte[(length + 1) * encoding];
        // 새 값이 음수면 맨 앞, 아니면 맨 뒤 자리 확보
        for (int i = length - 1; i >= 0; i--) {
            set(i + (prepend ? 1 : 0), readLe(old, i * oldEncoding, oldEncoding));
        }
        set(prepend ? 0 : length, value);
        length++;
    }

    private void resize(int newLength) {
        byte[] resized = new byte[newLength * encoding];
        System.arraycopy(contents, 0, resized, 0, Math.min(contents.length, resized.length));
        contents = resized;
    }

    private void moveTail(int from, int to) {
        int count = length - from;
        if (count > 0) {
            System.arraycopy(contents, from * encoding, contents, to * encoding, count * encoding);
        }
    }

    private long getEncoded(int pos, int enc) {
        return readLe(contents, pos * enc, enc);
    }

    private void set(int pos, long value) {
        writeLe(contents, pos * encoding, value, encoding);
    }

    private static long readLe(byte[] buf, int at, int width) {
        long v = 0;
        for (int i = 0; i < width; i++) {
            v |= (long) (buf[at + i] & 0xFF) << (8 * i);
        }
        int shift = 64 - 8 * width;
        return (v << shift) >> shift; // 부호 확장
    }

    private static void writeLe(byte[] buf, int at, long value, int width) {
        for (int i = 0; i < width; i++) {
            buf[at + i] = (byte) (value >>> (8 * i));
        }
    }
}
