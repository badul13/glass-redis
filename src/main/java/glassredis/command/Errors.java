package glassredis.command;

import glassredis.resp.RespValue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 표준 에러 응답 모음.
 *
 * <p>문구를 실제 Redis 와 똑같이 맞춘다. 클라이언트 라이브러리 중에는 에러 메시지를
 * 문자열로 비교하는 것들이 있어서, 다르게 쓰면 호환성이 깨진다.
 * 첫 단어({@code ERR})는 에러 종류를 나타내는 접두어로, Redis 의 관례다.
 *
 * <p>사용자가 보낸 바이트를 에러 문구에 담을 때는 두 가지를 조심한다.
 * 에러 응답은 한 줄짜리라 CR/LF 가 섞이면 프로토콜이 깨지므로 공백으로 바꾸고,
 * 실제 Redis 가 C 의 printf 로 문구를 만들기 때문에 생기는 동작(NUL 바이트에서 끊김, 길이 제한)도 따라 한다.
 */
public final class Errors {

    /** 에러 문구에 사용자 입력을 담을 때의 최대 바이트 수. 실제 Redis 와 같다. */
    private static final int ECHOED_INPUT_LIMIT = 128;

    private Errors() {
    }

    /**
     * 모르는 명령. Redis 7 은 인자 앞부분도 함께 보여준다.
     * <pre>ERR unknown command 'foo', with args beginning with: 'a' 'b' </pre>
     *
     * <p>명령 이름은 받은 그대로(대소문자 유지) 담는다. 이름은 128 바이트까지,
     * 인자는 목록 전체가 128 바이트를 넘기 전까지만 담는다.
     */
    public static RespValue.Err unknownCommand(byte[] name, List<byte[]> args) {
        ByteArrayOutputStream argsText = new ByteArrayOutputStream();
        for (int i = 0; i < args.size() && argsText.size() < ECHOED_INPUT_LIMIT; i++) {
            byte[] arg = cString(args.get(i), ECHOED_INPUT_LIMIT - argsText.size());
            argsText.write('\'');
            argsText.writeBytes(arg);
            argsText.write('\'');
            argsText.write(' ');
        }
        String message = "ERR unknown command '" + utf8(cString(name, ECHOED_INPUT_LIMIT))
                + "', with args beginning with: " + utf8(argsText.toByteArray());
        return new RespValue.Err(newlinesToSpaces(message));
    }

    public static RespValue.Err wrongNumberOfArguments(String commandName) {
        return new RespValue.Err(
                "ERR wrong number of arguments for '" + commandName.toLowerCase(Locale.ROOT) + "' command");
    }

    /** {@code OBJECT FOO} 처럼 하위 명령을 모른다. 하위 명령은 받은 그대로 보여주고, 명령 이름은 대문자다. */
    public static RespValue.Err unknownSubcommand(String subcommand, String commandName) {
        return new RespValue.Err(newlinesToSpaces("ERR unknown subcommand '" + subcommand + "'. Try "
                + commandName.toUpperCase(Locale.ROOT) + " HELP."));
    }

    public static RespValue.Err syntaxError() {
        return new RespValue.Err("ERR syntax error");
    }

    /**
     * 키에 든 값의 자료형이 명령과 맞지 않는다. List 에 {@code GET} 을 했을 때 같은 경우다.
     * 접두어가 {@code ERR} 이 아니라 {@code WRONGTYPE} 인 몇 안 되는 에러다.
     */
    public static RespValue.Err wrongType() {
        return new RespValue.Err("WRONGTYPE Operation against a key holding the wrong kind of value");
    }

    public static RespValue.Err notAnInteger() {
        return new RespValue.Err("ERR value is not an integer or out of range");
    }

    /** {@code LPOP k -1} 처럼 개수 자리에 음수(또는 정수가 아닌 값)를 줬다. */
    public static RespValue.Err mustBePositive() {
        return new RespValue.Err("ERR value is out of range, must be positive");
    }

    public static RespValue.Err invalidExpireTime(String commandName) {
        return new RespValue.Err(
                "ERR invalid expire time in '" + commandName.toLowerCase(Locale.ROOT) + "' command");
    }

    public static RespValue.Err notAFloat() {
        return new RespValue.Err("ERR value is not a valid float");
    }

    public static RespValue.Err minOrMaxNotAFloat() {
        return new RespValue.Err("ERR min or max is not a float");
    }

    /** {@code inf} 에 {@code -inf} 를 더한 것처럼 점수 계산 결과가 NaN 이 됐다. */
    public static RespValue.Err scoreIsNaN() {
        return new RespValue.Err("ERR resulting score is not a number (NaN)");
    }

    public static RespValue.Err zaddNxXxNotCompatible() {
        return new RespValue.Err("ERR XX and NX options at the same time are not compatible");
    }

    public static RespValue.Err zaddGtLtNxNotCompatible() {
        return new RespValue.Err("ERR GT, LT, and/or NX options at the same time are not compatible");
    }

    public static RespValue.Err zaddIncrSinglePair() {
        return new RespValue.Err("ERR INCR option supports a single increment-element pair");
    }

    public static RespValue.Err limitNeedsByScore() {
        return new RespValue.Err(
                "ERR syntax error, LIMIT is only supported in combination with either BYSCORE or BYLEX");
    }

    public static RespValue.Err hashValueNotAnInteger() {
        return new RespValue.Err("ERR hash value is not an integer");
    }

    public static RespValue.Err incrementOverflow() {
        return new RespValue.Err("ERR increment or decrement would overflow");
    }

    public static RespValue.Err decrementOverflow() {
        return new RespValue.Err("ERR decrement would overflow");
    }

    public static RespValue.Err stringTooLong() {
        return new RespValue.Err("ERR string exceeds maximum allowed size (proto-max-bulk-len)");
    }

    /** EXPIRE 계열의 모르는 옵션. 옵션을 받은 그대로 보여준다. */
    public static RespValue.Err unsupportedOption(byte[] option) {
        String message = "ERR Unsupported option " + utf8(cString(option, Integer.MAX_VALUE));
        return new RespValue.Err(newlinesToSpaces(trimTrailingNewlines(message)));
    }

    public static RespValue.Err expireNxNotCompatible() {
        return new RespValue.Err("ERR NX and XX, GT or LT options at the same time are not compatible");
    }

    public static RespValue.Err expireGtLtNotCompatible() {
        return new RespValue.Err("ERR GT and LT options at the same time are not compatible");
    }

    public static RespValue.Err protocol(String message) {
        return new RespValue.Err("ERR " + message);
    }

    public static RespValue.Err internal(String message) {
        return new RespValue.Err("ERR internal error: " + message);
    }

    /** C 문자열로 읽을 때처럼 NUL 바이트에서 끊고, 최대 {@code limit} 바이트까지만 쓴다. */
    private static byte[] cString(byte[] bytes, int limit) {
        int end = 0;
        while (end < bytes.length && end < limit && bytes[end] != 0) {
            end++;
        }
        return Arrays.copyOf(bytes, end);
    }

    private static String utf8(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String newlinesToSpaces(String text) {
        return text.replace('\r', ' ').replace('\n', ' ');
    }

    private static String trimTrailingNewlines(String text) {
        int end = text.length();
        while (end > 0 && (text.charAt(end - 1) == '\r' || text.charAt(end - 1) == '\n')) {
            end--;
        }
        return text.substring(0, end);
    }
}
