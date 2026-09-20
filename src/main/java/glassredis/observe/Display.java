package glassredis.observe;

import glassredis.resp.RespValue;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 키·값·응답을 화면에 그릴 수 있는 문자열로 줄이는 규칙.
 *
 * <p>여기서 줄이지 않으면 두 가지가 곤란해진다. 버퍼가 값 크기만큼 부풀고, 화면에 그리려면
 * 어차피 누군가는 잘라야 한다. 그래서 이벤트를 만들거나 스냅샷을 뜨는 순간에 잘라둔다.
 * 이 비용은 보고 있는 화면이 있을 때만 든다.
 */
final class Display {

    /** 인자를 최대 몇 개까지 보여줄지. 나머지는 개수로만 표시한다. */
    static final int MAX_ARGUMENTS = 8;

    /** 키·인자·응답 하나를 최대 몇 글자까지 보여줄지. */
    static final int MAX_TEXT_LENGTH = 40;

    private Display() {
    }

    /**
     * 바이트열을 화면용 문자열로 만든다.
     *
     * <p>UTF-8 로 읽는다. 한글 값을 넣고 대시보드에서 확인하는 게 이 프로젝트의 기본 사용법이라
     * 아스키가 아닌 글자를 점으로 바꾸지는 않는다. 대신 줄바꿈 같은 제어문자는 점으로 바꾼다 —
     * 한 줄에 그리는 스트림이라 개행이 섞이면 표가 깨진다.
     */
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

    /** 응답을 한 줄로 줄인다. 앞의 기호는 RESP 의 타입 바이트를 그대로 쓴다. */
    static String reply(RespValue value) {
        return switch (value) {
            case RespValue.SimpleString simple -> "+" + truncate(simple.text());
            // 에러 메시지에는 클라이언트가 보낸 인자가 섞여 들어온다(모르는 명령의 에러가 그렇다). 그래서 이것도 자른다.
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
            // 자른 자리가 하필 한 글자를 표현하는 두 char 사이라면, 짝을 잃은 앞쪽 char 를 버린다.
            // 그대로 두면 문자열이 아니라 깨진 바이트가 되고, 나중에 JSON 으로 내보낼 때 뭉개진다.
            if (!out.isEmpty() && Character.isHighSurrogate(out.charAt(out.length() - 1))) {
                out.setLength(out.length() - 1);
            }
            out.append('…');
        }
        return out.toString();
    }
}
