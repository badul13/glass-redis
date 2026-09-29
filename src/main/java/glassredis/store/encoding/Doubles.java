package glassredis.store.encoding;

import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;

/**
 * 실수를 Redis 와 같은 모양의 문자열로 쓴다. 응답({@code ZSCORE})과 listpack 안의 점수가 둘 다 이 모양이다.
 */
public final class Doubles {

    private Doubles() {
    }

    /**
     * 점수를 응답용 문자열로 만든다. 실제 Redis 7.4 에 같은 값을 넣고 {@code ZSCORE} 로 받아 본 모양에 맞췄다.
     * <pre>
     *   3.0        → "3"                      정수면 소수점 없이 (자바의 Double.toString 은 "3.0")
     *   1.0E18     → "1000000000000000000"    절댓값이 2^62 이하인 정수는 전부 정수로
     *   5.0E18     → "5e+18"                  그보다 크면 아래 규칙. 지수는 0 을 채우지 않는다
     *   0.1        → "0.1"                    자릿수는 되돌려 읽었을 때 같은 값이 되는 가장 짧은 것
     *   0.000001   → "0.000001"
     *   1.0E-7     → "1e-7"
     *   무한대     → "inf", "-inf"
     * </pre>
     * 정수가 아닌 값은 Redis 가 쓰는 fpconv 라이브러리의 모양 규칙을 옮겼다({@link #shortest}).
     *
     * <p>자릿수까지 완전히 같지는 않다. fpconv 는 Grisu2 알고리즘으로 자릿수를 뽑는데, 이건 빠른 대신
     * 가끔(무작위 값 500개 중 3개꼴) 가장 짧지 않은 자릿수를 낸다. {@code 6220561465e9} 를
     * {@code 6220561464999999000} 으로 쓰는 식이다. 여기서는 늘 가장 짧은 자릿수를 쓴다.
     * 두 문자열 모두 되돌려 읽으면 같은 double 이라 값이 달라지지는 않는다.
     */
    public static byte[] format(double value) {
        String text;
        if (Double.isInfinite(value)) {
            text = value > 0 ? "inf" : "-inf";
        } else if (isSmallInteger(value)) {
            text = Long.toString((long) value);
        } else {
            text = shortest(value);
        }
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * 가장 짧은 자릿수로 쓴다. 값을 {@code 자릿수 × 10^k} 로 보고, 자릿수와 k 에 따라 세 가지 모양 중 하나를 고른다.
     * <ul>
     *   <li>정수(k ≥ 0)이고 0 을 너무 많이 붙이지 않아도 되면 — 자릿수 뒤에 0 을 붙인다</li>
     *   <li>소수(k &lt; 0)이고 소수점 아래가 너무 길지 않으면 — 소수점 표기</li>
     *   <li>나머지 — {@code 1.5e+20} 같은 지수 표기</li>
     * </ul>
     */
    private static String shortest(double value) {
        // 자바 19 부터 Double.toString 은 되돌려 읽었을 때 같은 값이 되는 가장 짧은 자릿수를 준다.
        BigDecimal decimal = new BigDecimal(Double.toString(value)).stripTrailingZeros();
        // 단, 한 자리로도 되는데 두 자리를 주는 경우가 있다. 두 자리 중 원래 값에 더 가까운 걸 고르라는 사양 때문이다
        // (Double.MIN_VALUE 가 "4.9E-324" 로 나온다). 한 자리로 반올림해서 같은 값으로 돌아오면 그걸 쓴다.
        if (decimal.precision() == 2) {
            BigDecimal oneDigit = decimal.round(new MathContext(1));
            if (oneDigit.doubleValue() == value) {
                decimal = oneDigit.stripTrailingZeros();
            }
        }
        String digits = decimal.unscaledValue().abs().toString();
        int k = -decimal.scale();
        int exponent = k + digits.length() - 1;
        String sign = value < 0 ? "-" : "";

        if (k >= 0 && Math.abs(exponent) < digits.length() + 7) {
            return sign + digits + "0".repeat(k);
        }
        if (k < 0 && (k > -7 || Math.abs(exponent) < 4)) {
            return sign + decimal.abs().toPlainString();
        }
        String mantissa = digits.length() == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
        return sign + mantissa + "e" + (exponent < 0 ? "-" : "+") + Math.abs(exponent);
    }

    /**
     * {@link #format} 으로 쓴 문자열을 되읽는다. listpack 에 문자열로 들어간 점수를 꺼낼 때 쓴다(zzlStrtod).
     */
    public static double parse(byte[] text) {
        String s = new String(text, StandardCharsets.US_ASCII);
        return switch (s) {
            case "inf" -> Double.POSITIVE_INFINITY;
            case "-inf" -> Double.NEGATIVE_INFINITY;
            default -> Double.parseDouble(s);
        };
    }

    /** 2^62 이하의 정수인지(double2ll). 그렇다면 listpack 에 정수 인코딩으로 들어간다. */
    public static boolean isSmallInteger(double value) {
        return value == Math.rint(value) && Math.abs(value) <= 0x1p62;
    }
}
