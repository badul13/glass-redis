package glassredis.store.encoding;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** 실수를 Redis 7.2 와 같은 바이트로 - 응답(addReplyDouble)과 listpack 속 점수 표기 공통 */
public final class Doubles {

    /** MAX_D2STRING_CHARS 보다 넉넉히 - fpconv 최대 24바이트 + 부호 */
    private static final int BUFFER_SIZE = 32;

    private static final byte[] NAN = ascii("nan");
    private static final byte[] INF = ascii("inf");
    private static final byte[] NEGATIVE_INF = ascii("-inf");
    private static final byte[] ZERO = ascii("0");
    private static final byte[] NEGATIVE_ZERO = ascii("-0");

    private Doubles() {
    }

    /**
     * d2string (util.c) - ZSCORE·ZINCRBY 응답, listpack 의 정수 아닌 점수
     * - NaN → "nan", ±무한대 → "inf"/"-inf"
     * - 0 → 부호 있는 0 이면 "-0" (1/value < 0), 아니면 "0"
     * - 절댓값 2^62 이하 정수 → 정수 표기 (double2ll + ll2string)
     * - 그 밖 → fpconv_dtoa (Grisu2) - 1e23 → "99999999999999990000000" 처럼 최단이 아닐 때도 그대로
     */
    public static byte[] format(double value) {
        if (Double.isNaN(value)) {
            return NAN.clone();
        }
        if (Double.isInfinite(value)) {
            return (value < 0 ? NEGATIVE_INF : INF).clone();
        }
        if (value == 0) {
            return (1.0 / value < 0 ? NEGATIVE_ZERO : ZERO).clone();
        }
        if (isSmallInteger(value)) {
            return LongStrings.toBytes((long) value);
        }
        byte[] buf = new byte[BUFFER_SIZE];
        int len = FpconvDtoa.fpconvDtoa(value, buf);
        return Arrays.copyOf(buf, len);
    }

    /** format 결과 역파싱 - zzlStrtod (strtod) */
    public static double parse(byte[] text) {
        String s = new String(text, StandardCharsets.US_ASCII);
        return switch (s) {
            case "inf" -> Double.POSITIVE_INFINITY;
            case "-inf" -> Double.NEGATIVE_INFINITY;
            default -> Double.parseDouble(s);
        };
    }

    /**
     * double2ll - 절댓값 2^62(LLONG_MAX/2) 이하이면서 소수부 없으면 true
     * -0 도 정수 0 으로 참 - d2string 은 이 판정 전에 0 을 따로 처리, zzlInsertAt 은 바로 이 판정
     */
    public static boolean isSmallInteger(double value) {
        return value == Math.rint(value) && Math.abs(value) <= 0x1p62;
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }
}
