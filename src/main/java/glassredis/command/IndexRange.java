package glassredis.command;

/**
 * {@code LRANGE}, {@code LTRIM}, {@code ZRANGE} 가 받는 {@code start stop} 인덱스 쌍을 실제 위치로 바꾼 것.
 *
 * <p>규칙은 세 가지다.
 * <ul>
 *   <li>음수는 뒤에서부터 센다. {@code -1} 이 마지막 원소다.</li>
 *   <li>양 끝이 범위를 벗어나면 에러 대신 잘라서 맞춘다. {@code LRANGE k 0 1000} 은 원소가 3개여도 3개를 준다.</li>
 *   <li>{@code stop} 도 <b>포함</b>한다. {@code 0 -1} 이 전체다.</li>
 * </ul>
 *
 * @param start 첫 원소의 위치 (0부터)
 * @param end   마지막 원소의 위치. 포함한다.
 */
public record IndexRange(int start, int end) {

    /** 잘라서 맞춘 뒤에도 원소가 하나도 없으면 {@code null}. */
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
