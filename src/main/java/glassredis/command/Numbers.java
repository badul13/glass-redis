package glassredis.command;

import glassredis.store.encoding.Doubles;
import glassredis.store.encoding.LongStrings;

import java.nio.charset.StandardCharsets;
import java.util.OptionalDouble;
import java.util.OptionalLong;

/**
 * 인자 바이트 ↔ 숫자 변환
 * 정수 판정 - 재문자열화 시 원래 바이트와 같은 표기만 (Redis와 동일, "+5"·"007" 거절)
 */
public final class Numbers {

    private Numbers() {
    }

    /** 정수 아님 또는 long 범위 초과 시 empty - string2ll ({@link LongStrings#parse}) */
    public static OptionalLong parseLong(byte[] text) {
        Long value = LongStrings.parse(text);
        return value == null ? OptionalLong.empty() : OptionalLong.of(value);
    }

    public static byte[] toBytes(long value) {
        return LongStrings.toBytes(value);
    }

    /**
     * 점수 해석 - string2d (ZADD, ZINCRBY)
     * - strtod 가 전체 길이를 다 읽어야 함 - 뒤 공백·접미사·NUL 거절
     * - 첫 바이트 공백 거절
     * - 넘침(±무한대)·0 으로 사라지는 언더플로 거절, 비정규 수는 허용
     * - NaN 거절, inf/infinity 표기는 허용
     */
    public static OptionalDouble parseDouble(byte[] bytes) {
        if (bytes.length == 0 || isSpace(bytes[0])) {
            return OptionalDouble.empty();
        }
        Strtod parsed = strtod(bytes, 0);
        if (parsed.end() != bytes.length || parsed.rangeError() || Double.isNaN(parsed.value())) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(parsed.value());
    }

    /**
     * 점수 구간 끝 해석 - zslParseRange 의 strtod 부분
     * - 통과 조건 - 끝 위치가 C 문자열 끝 (eptr[0] == '\0'), NaN 아님
     * - 앞 공백·넘침·언더플로·빈 문자열(0) 허용
     *
     * @param from '(' 다음 위치 등 해석 시작
     */
    public static OptionalDouble parseRangeBound(byte[] bytes, int from) {
        Strtod parsed = strtod(bytes, from);
        boolean atEnd = parsed.end() == bytes.length || bytes[parsed.end()] == 0;
        if (!atEnd || Double.isNaN(parsed.value())) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(parsed.value());
    }

    /** 규칙 - {@link Doubles#format} */
    public static byte[] formatDouble(double value) {
        return Doubles.format(value);
    }

    /**
     * strtod 결과
     *
     * @param end        해석이 멈춘 위치 - 변환 없으면 시작 위치 (endptr == nptr)
     * @param rangeError ERANGE 중 string2d 가 거부하는 경우 - 무한대로 넘침, 0 으로 언더플로
     */
    private record Strtod(double value, int end, boolean rangeError) {
    }

    /**
     * glibc strtod (C 로캘) 흉내 - 문법 판정은 직접, 값 계산은 Double.parseDouble
     * - 앞 공백 → 부호 → inf/infinity, nan[(...)], 0x 16진수(지수 p 선택), 10진수(지수 e 선택)
     * - 지수부는 숫자가 하나 이상일 때만 소비 - "1e" 는 "1" 까지
     * - NUL 은 어느 문법에도 안 맞음 - C 문자열 끝과 같은 효과
     */
    private static Strtod strtod(byte[] s, int start) {
        int i = start;
        while (i < s.length && isSpace(s[i])) {
            i++;
        }
        int signAt = i;
        boolean negative = false;
        if (i < s.length && (s[i] == '+' || s[i] == '-')) {
            negative = s[i] == '-';
            i++;
        }

        if (startsWithIgnoreCase(s, i, "inf")) {
            int end = startsWithIgnoreCase(s, i, "infinity") ? i + 8 : i + 3;
            return new Strtod(negative ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY, end, false);
        }
        if (startsWithIgnoreCase(s, i, "nan")) {
            int end = i + 3;
            // nan(n-char-sequence) - 닫는 괄호까지 있어야 소비
            if (end < s.length && s[end] == '(') {
                int j = end + 1;
                while (j < s.length && (isAlnum(s[j]) || s[j] == '_')) {
                    j++;
                }
                if (j < s.length && s[j] == ')') {
                    end = j + 1;
                }
            }
            return new Strtod(Double.NaN, end, false);
        }

        boolean hex = i + 1 < s.length && s[i] == '0' && (s[i + 1] == 'x' || s[i + 1] == 'X');
        int mantissaStart = hex ? i + 2 : i;
        int j = mantissaStart;
        int digits = 0;
        boolean nonZero = false;
        while (j < s.length && isDigit(s[j], hex)) {
            nonZero |= s[j] != '0';
            digits++;
            j++;
        }
        if (j < s.length && s[j] == '.') {
            j++;
            while (j < s.length && isDigit(s[j], hex)) {
                nonZero |= s[j] != '0';
                digits++;
                j++;
            }
        }
        if (digits == 0) {
            // "0x" 뒤 숫자 없음 - "0" 까지만 변환
            return hex ? new Strtod(negative ? -0.0 : 0.0, i + 1, false) : new Strtod(0, start, false);
        }
        int mantissaEnd = j;
        int end = exponentEnd(s, mantissaEnd, hex ? 'p' : 'e');

        String text = ascii(s, signAt, mantissaEnd) + (end > mantissaEnd ? ascii(s, mantissaEnd, end) : hex ? "p0" : "");
        double value;
        try {
            value = Double.parseDouble(text);
        } catch (NumberFormatException e) {
            // 자바가 못 읽는 거대 지수 - 부호로 넘침/언더플로 판단
            boolean tinyExponent = end > mantissaEnd && s[mantissaEnd + 1] == '-';
            value = tinyExponent ? 0 : Double.POSITIVE_INFINITY;
            value = negative ? -value : value;
        }
        boolean rangeError = Double.isInfinite(value) || (value == 0 && nonZero);
        return new Strtod(value, end, rangeError);
    }

    /** 지수부 끝 - 기호, 선택 부호, 10진 숫자 하나 이상 - 없으면 from 그대로 */
    private static int exponentEnd(byte[] s, int from, char marker) {
        if (from >= s.length || (s[from] | 0x20) != marker) {
            return from;
        }
        int k = from + 1;
        if (k < s.length && (s[k] == '+' || s[k] == '-')) {
            k++;
        }
        if (k >= s.length || !isDigit(s[k], false)) {
            return from;
        }
        while (k < s.length && isDigit(s[k], false)) {
            k++;
        }
        return k;
    }

    private static boolean startsWithIgnoreCase(byte[] s, int from, String word) {
        if (from + word.length() > s.length) {
            return false;
        }
        for (int k = 0; k < word.length(); k++) {
            if ((s[from + k] | 0x20) != word.charAt(k)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isDigit(byte b, boolean hex) {
        return (b >= '0' && b <= '9') || (hex && ((b | 0x20) >= 'a' && (b | 0x20) <= 'f'));
    }

    private static boolean isAlnum(byte b) {
        return isDigit(b, false) || ((b | 0x20) >= 'a' && (b | 0x20) <= 'z');
    }

    private static String ascii(byte[] s, int from, int to) {
        return new String(s, from, to - from, StandardCharsets.US_ASCII);
    }

    /** C isspace - 공백, \t \n \v \f \r */
    private static boolean isSpace(byte b) {
        return b == ' ' || (b >= '\t' && b <= '\r');
    }
}
