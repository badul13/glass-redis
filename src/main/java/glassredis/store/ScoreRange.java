package glassredis.store;

/**
 * 점수 구간. {@code ZRANGEBYSCORE k (1 5} 의 {@code (1 5} 부분이다. 양 끝을 따로 포함/제외할 수 있다.
 *
 * @param minExclusive {@code true} 면 {@code min} 과 같은 점수는 빠진다
 * @param maxExclusive {@code true} 면 {@code max} 와 같은 점수는 빠진다
 */
public record ScoreRange(double min, boolean minExclusive, double max, boolean maxExclusive) {

    public boolean aboveMin(double score) {
        return minExclusive ? score > min : score >= min;
    }

    public boolean belowMax(double score) {
        return maxExclusive ? score < max : score <= max;
    }

    /** 어떤 점수도 들어올 수 없는 구간인지. {@code 5 1} 이나 {@code (3 3} 같은 것. */
    public boolean isEmpty() {
        return min > max || (min == max && (minExclusive || maxExclusive));
    }
}
