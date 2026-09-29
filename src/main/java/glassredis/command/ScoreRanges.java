package glassredis.command;

import glassredis.store.ScoreRange;

import java.util.Arrays;
import java.util.OptionalDouble;

/**
 * {@code ZRANGEBYSCORE}, {@code ZCOUNT} 가 받는 점수 구간 {@code min max} 를 읽는다.
 *
 * <p>기본은 양 끝을 포함한다. 앞에 {@code (} 를 붙이면 그 끝은 뺀다.
 * {@code -inf}, {@code +inf} 로 "끝까지"를 나타낸다.
 * <pre>
 *   ZCOUNT k 1 5          1 ≤ 점수 ≤ 5
 *   ZCOUNT k (1 5         1 &lt; 점수 ≤ 5
 *   ZCOUNT k -inf (5      점수 &lt; 5
 * </pre>
 */
public final class ScoreRanges {

    private ScoreRanges() {
    }

    /** 둘 중 하나라도 실수가 아니면 {@code null}. */
    public static ScoreRange parse(byte[] min, byte[] max) {
        boolean minExclusive = isExclusive(min);
        boolean maxExclusive = isExclusive(max);
        OptionalDouble minValue = Numbers.parseDouble(minExclusive ? Arrays.copyOfRange(min, 1, min.length) : min);
        OptionalDouble maxValue = Numbers.parseDouble(maxExclusive ? Arrays.copyOfRange(max, 1, max.length) : max);
        if (minValue.isEmpty() || maxValue.isEmpty()) {
            return null;
        }
        return new ScoreRange(minValue.getAsDouble(), minExclusive, maxValue.getAsDouble(), maxExclusive);
    }

    private static boolean isExclusive(byte[] bound) {
        return bound.length > 0 && bound[0] == '(';
    }
}
