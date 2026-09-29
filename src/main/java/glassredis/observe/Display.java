package glassredis.observe;

import glassredis.resp.RespValue;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** 키·인자·응답의 화면용 자르기 - 버퍼가 값 크기만큼 부풀지 않게 발행 시점에 처리 */
final class Display {

    /** 초과 인자는 개수로만 표시 */
    static final int MAX_ARGUMENTS = 8;

    static final int MAX_TEXT_LENGTH = 40;

    private Display() {
    }

    /** UTF-8 디코딩 - 한 줄 표시라 제어문자는 점으로 치환 */
    static String text(byte[] bytes) {
        return truncate(new String(bytes, StandardCharsets.UTF_8));
    }

    static String arguments(List<byte[]> args) {
        StringBuilder out = new StringBuilder();
        int shown = Math.min(args.size(), MAX_ARGUMENTS);
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                out.append(' ');
            }
            out.append(text(args.get(i)));
        }
        if (args.size() > shown) {
            out.append(" …(+").append(args.size() - shown).append(')');
        }
        return out.toString();
    }

    /** 앞 기호 - RESP 타입 바이트 */
    static String reply(RespValue value) {
        return switch (value) {
            case RespValue.SimpleString simple -> "+" + truncate(simple.text());
            // 에러 메시지에 클라이언트 인자 포함 가능 - 자르기 대상
            case RespValue.Err error -> "-" + truncate(error.message());
            case RespValue.Int number -> ":" + number.value();
            case RespValue.BulkString bulk -> text(bulk.bytes());
            case RespValue.Array array -> "(" + array.items().size() + "개)";
            case RespValue.Nil ignored -> "(nil)";
        };
    }

    static String truncate(String text) {
        int length = Math.min(text.length(), MAX_TEXT_LENGTH);
        StringBuilder out = new StringBuilder(length + 1);
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            out.append(Character.isISOControl(c) ? '.' : c);
        }
        if (text.length() > length) {
            // 서로게이트 쌍 중간 절단 시 짝 잃은 앞쪽 제거 - 남기면 JSON 깨짐
            if (!out.isEmpty() && Character.isHighSurrogate(out.charAt(out.length() - 1))) {
                out.setLength(out.length() - 1);
            }
            out.append('…');
        }
        return out.toString();
    }
}
