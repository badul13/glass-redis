package glassredis.command;

/**
 * LRANGE/LTRIM/ZRANGE의 start stop → 실제 위치
 * 음수는 뒤에서부터, 범위 밖은 절단, end 포함
 */
public record IndexRange(int start, int end) {

    /** 절단 후 빈 구간이면 null */
    public static IndexRange of(long start, long stop, int length) {
        if (start < 0) {
            start = Math.max(start + length, 0);
        }
        if (stop < 0) {
            stop += length;
        }
        if (stop >= length) {
            stop = length - 1;
        }
        if (start > stop || start >= length) {
            return null;
        }
        return new IndexRange((int) start, (int) stop);
    }

    public int count() {
        return end - start + 1;
    }
}
