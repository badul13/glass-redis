package glassredis.command;

import glassredis.store.encoding.Doubles;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.OptionalLong;

/**
 * 인자로 받은 바이트를 숫자로 해석하고, 숫자를 응답용 바이트로 되돌린다.
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

    /**
     * Sorted Set 점수로 쓸 실수로 해석한다. 실수가 아니거나 NaN 이면 비어 있다.
     *
     * <p>실제 Redis 는 C 의 {@code strtod} 로 읽는다. {@code Double.parseDouble} 과 대체로 같지만 몇 군데가 달라서 맞춘다.
     * <ul>
     *   <li>{@code inf}, {@code -inf}, {@code +inf}, {@code infinity} 를 대소문자 없이 받는다. 자바는 {@code Infinity} 만 안다.</li>
     *   <li>앞뒤 공백을 받지 않는다. 자바는 알아서 잘라낸다.</li>
     *   <li>{@code 1.5d}, {@code 2f} 처럼 자바 리터럴 접미사가 붙은 걸 받지 않는다.</li>
     *   <li>{@code 0x10} 처럼 지수부 없는 16진수를 받는다. 자바는 {@code 0x10p0} 처럼 {@code p} 가 있어야 받는다.</li>
     *   <li>NaN 은 점수로 쓸 수 없으니 거절한다. 순서를 매길 수 없는 값이다.</li>
     *   <li>{@code 1e400} 처럼 double 범위를 넘는 수를 거절한다. 자바는 무한대로 바꿔 받아준다.</li>
     * </ul>
     */
    public static OptionalDouble parseDouble(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.US_ASCII);
        if (text.isEmpty() || isSpace(text.charAt(0)) || isSpace(text.charAt(text.length() - 1))) {
            return OptionalDouble.empty();
        }
        boolean negative = text.charAt(0) == '-';
        String unsigned = text.charAt(0) == '-' || text.charAt(0) == '+' ? text.substring(1) : text;
        if (unsigned.equalsIgnoreCase("inf") || unsigned.equalsIgnoreCase("infinity")) {
            return OptionalDouble.of(negative ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY);
        }
        String lower = unsigned.toLowerCase(Locale.ROOT);
        if (lower.startsWith("0x")) {
            // 16진수. 자바는 지수부(p)가 꼭 있어야 받는데 strtod 는 없어도 받는다. 끝의 f 는 여기서는 숫자다.
            if (lower.indexOf('p') < 0) {
                text += "p0";
            }
        } else if (lower.endsWith("d") || lower.endsWith("f")) {
            return OptionalDouble.empty();
        }
        try {
            double value = Double.parseDouble(text);
            return Double.isNaN(value) || Double.isInfinite(value) ? OptionalDouble.empty() : OptionalDouble.of(value);
        } catch (NumberFormatException e) {
            return OptionalDouble.empty();
        }
    }

    /** 점수를 응답용 문자열로 만든다. 모양 규칙은 {@link Doubles#format}. */
    public static byte[] formatDouble(double value) {
        return Doubles.format(value);
    }


    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == 0x0B;
    }
}
