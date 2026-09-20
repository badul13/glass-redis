package glassredis.observe;

/**
 * JSON 문자열을 쓰는 최소한의 도구.
 *
 * <p>라이브러리를 하나 받아 쓰지 않는 이유는, 이 프로젝트가 내보내는 JSON 의 모양이 전부
 * 여기 있는 몇 개의 record 로 고정돼 있어서다. 임의의 객체를 다룰 필요가 없으니
 * 필요한 건 문자열 하나를 안전하게 따옴표로 감싸는 일뿐이다.
 *
 * <p>이스케이프를 빠뜨리면 값에 들어간 따옴표 하나로 스트림 전체가 깨진다.
 * 키와 값은 사용자가 아무 바이트나 넣을 수 있는 자리이므로 이 처리는 선택이 아니다.
 */
final class Json {

    private Json() {
    }

    /** 문자열을 이스케이프해서 따옴표까지 붙인다. {@code null} 은 JSON 의 null 이 된다. */
    static void string(StringBuilder out, String text) {
        if (text == null) {
            out.append("null");
            return;
        }
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    /** {@code "이름":값} 앞에 쉼표가 필요한지까지 봐주는 도우미. */
    static void field(StringBuilder out, String name, String value) {
        separate(out);
        string(out, name);
        out.append(':');
        string(out, value);
    }

    static void field(StringBuilder out, String name, long value) {
        separate(out);
        string(out, name);
        out.append(':').append(value);
    }

    static void field(StringBuilder out, String name, Long value) {
        separate(out);
        string(out, name);
        out.append(':');
        out.append(value == null ? "null" : value.toString());
    }

    /** {@code "이름":} 까지만 쓴다. 뒤에 배열이나 객체가 이어질 때 쓴다. */
    static void name(StringBuilder out, String name) {
        separate(out);
        string(out, name);
        out.append(':');
    }

    /** 바로 앞이 여는 괄호가 아니면 쉼표를 찍는다. */
    private static void separate(StringBuilder out) {
        char last = out.charAt(out.length() - 1);
        if (last != '{' && last != '[') {
            out.append(',');
        }
    }
}
