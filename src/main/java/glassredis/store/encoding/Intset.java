package glassredis.store.encoding;

/**
 * intset — 정수만 든 Set 을 정렬된 정수 배열 하나로 담는다. Redis 7.2 {@code intset.c} 를 옮겼다.
 *
 * <p>원소마다 2바이트, 4바이트, 8바이트 중 <b>모두에게 같은 폭</b>을 쓴다. 폭은 가장 큰(또는 가장 작은) 값이
 * 정한다. 1, 2, 3 만 있으면 2바이트씩이고, 여기에 100000 이 들어오는 순간 배열 전체가 4바이트 폭으로 다시 쓰인다
 * (업그레이드). 한 번 넓어진 폭은 그 값을 지워도 좁아지지 않는다.
 *
 * <p>정렬돼 있으니 찾기는 이진 탐색 O(log n) 이고, 넣고 빼기는 뒤를 밀어야 해서 O(n) 이다.
 * 그래서 Redis 는 원소가 512개({@code set-max-intset-entries})를 넘으면 해시 테이블로 바꾼다.
 *
 * <p>바이트 배치는 C 판과 같다: {@code [폭 4B][원소 수 4B][원소...]}, 전부 리틀 엔디언.
 */
public final class Intset {

    static final int ENC_INT16 = 2;
    static final int ENC_INT32 = 4;
    static final int ENC_INT64 = 8;
    private static final int HEADER = 8;

    private int encoding = ENC_INT16;
    private int length;
    /** 원소들만. 헤더는 {@link #rawBytes} 에서 붙인다. */
    private byte[] contents = new byte[0];

    public int length() {
        return length;
    }

    /** 원소 하나의 폭(바이트). */
    public int encoding() {
        return encoding;
    }

    /** C 판과 같은 크기: 헤더 8바이트 + 원소 수 × 폭. */
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

    /** 넣었으면 {@code true}, 이미 있었으면 {@code false}. */
    public boolean add(long value) {
        if (valueEncoding(value) > encoding) {
            // 지금 폭에 안 들어가는 값이면, 그 값은 기존의 모든 값보다 크거나 작다. 그래서 맨 앞이나 맨 뒤에 들어간다.
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

    /** 뺐으면 {@code true}. 폭은 줄이지 않는다. */
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

    /** 대시보드가 바이트를 그대로 보여줄 때 쓴다. */
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

    /** 찾으면 위치, 못 찾으면 {@code -(넣을 자리) - 1}. */
    private int search(long value) {
        if (length == 0) {
            return -1;
        }
        // 양 끝 밖이면 탐색할 것도 없이 넣을 자리를 안다.
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
        // 뒤에서부터 넓은 폭으로 옮겨 쓴다. 새 값이 음수면 맨 앞 한 칸을 비워 둔다.
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

    /** 부호 있는 리틀 엔디언 정수를 읽는다. */
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
