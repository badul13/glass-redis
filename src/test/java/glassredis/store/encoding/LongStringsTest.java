package glassredis.store.encoding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** string2ll·ll2string - 명령 인자, int 인코딩, listpack·intset 정수 판정 공용 규칙 */
class LongStringsTest {

    @Test
    @DisplayName("되돌렸을 때 같은 바이트가 되는 표기만 정수 - long 범위 양 끝 포함")
    void parsesCanonicalForms() {
        assertEquals(0L, LongStrings.parse(bytes("0")));
        assertEquals(-1L, LongStrings.parse(bytes("-1")));
        assertEquals(Long.MAX_VALUE, LongStrings.parse(bytes("9223372036854775807")));
        assertEquals(Long.MIN_VALUE, LongStrings.parse(bytes("-9223372036854775808")));
    }

    @Test
    @DisplayName("거절 - 앞자리 0, 부호 +, -0, 공백, 빈 문자열, 부호만, 범위 넘침, 21자 이상")
    void rejectsNonCanonicalForms() {
        for (String text : new String[] {"007", "+1", "-0", " 1", "1 ", "", "-", "1a",
                "9223372036854775808", "-9223372036854775809", "18446744073709551616", "000000000000000000001"}) {
            assertNull(LongStrings.parse(bytes(text)), "정수로 판정: " + text);
        }
    }

    @Test
    @DisplayName("ll2string - 10진 ASCII")
    void formats() {
        assertArrayEquals(bytes("-9223372036854775808"), LongStrings.toBytes(Long.MIN_VALUE));
        assertArrayEquals(bytes("0"), LongStrings.toBytes(0));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }
}
