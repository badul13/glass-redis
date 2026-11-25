package glassredis.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class StringValueTest {

    @Test
    @DisplayName("내용 기준 비교 - 배열이 달라도 바이트와 인코딩이 같으면 같은 값")
    void equalsByContent() {
        StringValue a = StringValue.of(bytes("hello"));
        StringValue b = StringValue.of(bytes("hello"));

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, StringValue.raw(bytes("hello")), "인코딩이 다르면 다른 값");
        assertNotEquals(a, StringValue.of(bytes("hellO")));
    }

    @Test
    @DisplayName("toString 은 배열 주소 대신 내용과 인코딩")
    void readableToString() {
        assertEquals("StringValue[hello, embstr]", StringValue.of(bytes("hello")).toString());
        assertEquals("StringValue[12, int]", StringValue.of(bytes("12")).toString());
    }

    @Test
    @DisplayName("인코딩 판정 - 정수 표기 20자 이하면 int, 44바이트 이하면 embstr, 그 외 raw")
    void encoding() {
        assertEquals("int", StringValue.of(bytes("-9223372036854775808")).encoding());
        assertEquals("embstr", StringValue.of(bytes("9223372036854775808")).encoding());
        assertEquals("embstr", StringValue.of(bytes("007")).encoding());
        assertEquals("embstr", StringValue.of(bytes("x".repeat(44))).encoding());
        assertEquals("raw", StringValue.of(bytes("x".repeat(45))).encoding());
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
