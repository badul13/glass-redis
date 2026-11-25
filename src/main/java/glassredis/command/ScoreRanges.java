package glassredis.command;

import glassredis.store.ScoreRange;

import java.util.OptionalDouble;

/**
 * ZRANGEBYSCORE/ZCOUNT의 min max 파싱 - zslParseRange
 * 기본 포함, ( 접두 시 제외, 숫자 해석은 {@link Numbers#parseRangeBound} (ZADD 의 string2d 보다 너그러움)
 */
public final class ScoreRanges {

    private ScoreRanges() {
    }

    /** 둘 중 하나라도 실수 아니면 null */
    public static ScoreRange parse(byte[] min, byte[] max) {
        boolean minExclusive = isExclusive(min);
        boolean maxExclusive = isExclusive(max);
        OptionalDouble minValue = Numbers.parseRangeBound(min, minExclusive ? 1 : 0);
        OptionalDouble maxValue = Numbers.parseRangeBound(max, maxExclusive ? 1 : 0);
        if (minValue.isEmpty() || maxValue.isEmpty()) {
            return null;
        }
        return new ScoreRange(minValue.getAsDouble(), minExclusive, maxValue.getAsDouble(), maxExclusive);
    }

    private static boolean isExclusive(byte[] bound) {
        return bound.length > 0 && bound[0] == '(';
    }
}
