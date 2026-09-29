package glassredis.store;

/** 점수 구간 - ZRANGEBYSCORE k (1 5 처럼 양 끝 개별 제외 가능 */
public record ScoreRange(double min, boolean minExclusive, double max, boolean maxExclusive) {

    public boolean aboveMin(double score) {
        return minExclusive ? score > min : score >= min;
    }

    public boolean belowMax(double score) {
        return maxExclusive ? score < max : score <= max;
    }

    /** 빈 구간 - 5 1, (3 3 등 */
    public boolean isEmpty() {
        return min > max || (min == max && (minExclusive || maxExclusive));
    }
}
