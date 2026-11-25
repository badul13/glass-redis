package glassredis.store.encoding;

import java.nio.charset.StandardCharsets;

/**
 * 10진 정수 표기 ↔ long - Redis util.c string2ll, ll2string
 * 명령 인자 해석, 문자열 int 인코딩 판정, listpack·intset 정수 판정이 모두 이 규칙 하나
 */
public final class LongStrings {

    /** LONG_STR_SIZE - long 10진수 표기 최대 길이 + 1 */
    private static final int LONG_STR_SIZE = 21;

    private LongStrings() {
    }

    /**
     * 되돌렸을 때 같은 바이트가 되는 표기만 정수 인정 - string2ll, 아니면 null
     * "007", "+1", "-0", " 1", "1 " 은 정수 아님, 범위 넘침도 null
     */
    public static Long parse(byte[] s) {
        int len = s.length;
        if (len == 0 || len >= LONG_STR_SIZE) {
            return null;
        }
        if (len == 1 && s[0] == '0') {
            return 0L;
        }
        int i = 0;
        boolean negative = false;
        if (s[0] == '-') {
            negative = true;
            i++;
            if (i == len) {
                return null;
            }
        }
        if (s[i] < '1' || s[i] > '9') {
            return null;
        }
        // 2^63까지 담기 위해 부호 없는 64비트로 누적
        long v = s[i++] - '0';
        while (i < len && s[i] >= '0' && s[i] <= '9') {
            if (Long.compareUnsigned(v, Long.divideUnsigned(-1L, 10)) > 0) {
                return null;
            }
            v *= 10;
            int digit = s[i] - '0';
            if (Long.compareUnsigned(v, -1L - digit) > 0) {
                return null;
            }
            v += digit;
            i++;
        }
        if (i < len) {
            return null;
        }
        if (negative) {
            // -(INT64_MIN+1)+1 = 2^63
            if (Long.compareUnsigned(v, Long.MIN_VALUE) > 0) {
                return null;
            }
            return -v;
        }
        if (Long.compareUnsigned(v, Long.MAX_VALUE) > 0) {
            return null;
        }
        return v;
    }

    /** ll2string */
    public static byte[] toBytes(long value) {
        return Long.toString(value).getBytes(StandardCharsets.US_ASCII);
    }
}
