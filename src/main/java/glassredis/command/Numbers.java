package glassredis.command;

import glassredis.store.encoding.Doubles;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.OptionalLong;

/**
 * 인자 바이트 ↔ 숫자 변환
 * 정수 판정 - 재문자열화 시 원래 바이트와 같은 표기만 (Redis와 동일, "+5"·"007" 거절)
 */
public final class Numbers {

    /** "-9223372036854775808"의 길이 */
    private static final int MAX_LONG_TEXT_LENGTH = 20;

    private Numbers() {
    }

    /** 정수 아님 또는 long 범위 초과 시 empty */
    public static OptionalLong parseLong(byte[] text) {
        if (text.length == 0 || text.length > MAX_LONG_TEXT_LENGTH) {
            return OptionalLong.empty();
        }
        boolean negative = text[0] == '-';
        int start = negative ? 1 : 0;
        if (start == text.length) {
            return OptionalLong.empty(); // "-" 단독
        }
        // "007", "-0" 거절
        if (text[start] == '0') {
            return text.length == 1 ? OptionalLong.of(0) : OptionalLong.empty();
        }

        // Long.MIN_VALUE 표현 위해 음수 쪽으로 누적
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
            return OptionalLong.empty(); // "9223372036854775808" - 양수로 표현 불가
        }
        return OptionalLong.of(-result);
    }

    public static byte[] toBytes(long value) {
        return Long.toString(value).getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * 점수 해석 - 실수 아님, NaN, double 범위 초과 시 empty
     * Redis strtod 기준, Double.parseDouble과의 차이
     * - inf/infinity 허용, 대소문자 무관
     * - 앞뒤 공백, d/f 접미사 거절
     * - 지수부 없는 16진수 허용
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
            // 자바 16진수는 지수부(p) 필수 - 여기서 끝의 f는 숫자
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

    /** 규칙 - {@link Doubles#format} */
    public static byte[] formatDouble(double value) {
        return Doubles.format(value);
    }


    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == 0x0B;
    }
}
