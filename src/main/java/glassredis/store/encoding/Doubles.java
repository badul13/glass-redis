package glassredis.store.encoding;

import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;

/** 실수를 Redis와 같은 모양의 문자열로 - 응답과 listpack 속 점수 표기 */
public final class Doubles {

    private Doubles() {
    }

    /**
     * Redis 7.4 ZSCORE 출력 기준 - 3.0 → "3", 5.0E18 → "5e+18", 1.0E-7 → "1e-7", 무한대 → "inf"
     * 정수 아니면 fpconv 모양 규칙
     * fpconv(Grisu2)와 차이 - 드물게 최단 아닌 자릿수를 내는 fpconv와 달리 항상 최단 자릿수
     * 되읽은 double 값은 동일
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

    /** 값을 자릿수 × 10^k로 보고 정수·소수점·지수 표기 중 선택 - fpconv */
    private static String shortest(double value) {
        // 자바 19+ Double.toString은 최단 왕복 자릿수
        BigDecimal decimal = new BigDecimal(Double.toString(value)).stripTrailingZeros();
        // 사양상 한 자리로 충분한데 두 자리 반환 사례 - Double.MIN_VALUE → "4.9E-324"
        // 한 자리로 되면 한 자리 사용
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

    /** format 결과 역파싱 - zzlStrtod */
    public static double parse(byte[] text) {
        String s = new String(text, StandardCharsets.US_ASCII);
        return switch (s) {
            case "inf" -> Double.POSITIVE_INFINITY;
            case "-inf" -> Double.NEGATIVE_INFINITY;
            default -> Double.parseDouble(s);
        };
    }

    /** 절댓값 2^62 이하 정수 여부 - double2ll, 참이면 정수 표기 */
    public static boolean isSmallInteger(double value) {
        return value == Math.rint(value) && Math.abs(value) <= 0x1p62;
    }
}
