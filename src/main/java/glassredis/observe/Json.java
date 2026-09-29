package glassredis.observe;

/** 고정된 몇 가지 모양만 출력 - 라이브러리 없이 직접 작성 */
final class Json {

    private Json() {
    }

    /** null은 JSON null */
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

    static void field(StringBuilder out, String name, boolean value) {
        separate(out);
        string(out, name);
        out.append(':').append(value);
    }

    /** JSON에 무한대 없음 - Redis 표기대로 "inf", "-inf" 문자열, NaN 입력 없음 */
    static void field(StringBuilder out, String name, double value) {
        separate(out);
        string(out, name);
        out.append(':');
        if (Double.isInfinite(value)) {
            string(out, value > 0 ? "inf" : "-inf");
        } else {
            out.append(value);
        }
    }

    /** "이름": 까지만 출력 */
    static void name(StringBuilder out, String name) {
        separate(out);
        string(out, name);
        out.append(':');
    }

    private static void separate(StringBuilder out) {
        char last = out.charAt(out.length() - 1);
        if (last != '{' && last != '[') {
            out.append(',');
        }
    }
}
