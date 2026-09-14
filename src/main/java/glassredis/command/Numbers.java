package glassredis.command;

import java.nio.charset.StandardCharsets;
import java.util.OptionalLong;

/**
 * 인자로 받은 바이트를 정수로 해석한다.
 *
 * <p>{@code Long.parseLong} 을 쓰지 않고 직접 짠다. 그쪽은 {@code "+5"} 나 {@code "007"} 도 5, 7 로 받아주는데,
 * Redis 는 이런 표기를 정수로 보지 않는다. 다시 문자열로 되돌렸을 때 원래 바이트와 똑같아지는 표기만 정수로 인정한다.
 * 그래야 {@code "007"} 에 {@code INCR} 을 했을 때 앞의 0 이 소리 없이 사라지는 일이 없다.
 */
public final class Numbers {

    /** {@code "-9223372036854775808"} 의 길이. 이보다 길면 볼 것도 없이 범위 밖이다. */
    private static final int MAX_LONG_TEXT_LENGTH = 20;

    private Numbers() {
    }

    /** 부호 있는 64비트 정수로 해석한다. 정수가 아니거나 범위를 넘으면 비어 있다. */
    public static OptionalLong parseLong(byte[] text) {
        if (text.length == 0 || text.length > MAX_LONG_TEXT_LENGTH) {
            return OptionalLong.empty();
        }
        boolean negative = text[0] == '-';
        int start = negative ? 1 : 0;
        if (start == text.length) {
            return OptionalLong.empty(); // "-" 하나뿐
        }
        // 0 으로 시작해도 되는 건 "0" 하나뿐이다. "007", "-0" 은 거절한다.
        if (text[start] == '0') {
            return text.length == 1 ? OptionalLong.of(0) : OptionalLong.empty();
        }

        // 음수 쪽으로 누적한다. long 은 음수 쪽 범위가 하나 더 넓어서(-2^63 .. 2^63-1),
        // 양수로 누적하면 "-9223372036854775808" 을 담는 도중에 넘쳐버린다.
        long result = 0;
        for (int i = start; i < text.length; i++) {
            int digit = text[i] - '0';
            if (digit < 0 || digit > 9) {
                return OptionalLong.empty();
            }
            try {
                result = Math.subtractExact(Math.multiplyExact(result, 10), digit);
            } catch (ArithmeticException overflow) {
                return OptionalLong.empty();
            }
        }
        if (negative) {
            return OptionalLong.of(result);
        }
        if (result == Long.MIN_VALUE) {
            return OptionalLong.empty(); // "9223372036854775808" 은 양수로 표현할 수 없다
        }
        return OptionalLong.of(-result);
    }

    public static byte[] toBytes(long value) {
        return Long.toString(value).getBytes(StandardCharsets.US_ASCII);
    }
}
