package glassredis.command;

import glassredis.store.ScoreRange;

import java.util.Arrays;
import java.util.OptionalDouble;

/** ZRANGEBYSCORE/ZCOUNT의 min max 파싱 - 기본 포함, ( 접두 시 제외 */
public final class ScoreRanges {

    private ScoreRanges() {
    }

    /** 둘 중 하나라도 실수 아니면 null */
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
