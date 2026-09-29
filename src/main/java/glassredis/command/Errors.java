package glassredis.command;

import glassredis.resp.RespValue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 에러 응답 모음 - 문자열 비교 클라이언트 때문에 문구는 Redis와 동일
 * 사용자 입력 포함 시 CR/LF는 공백으로 치환
 * Redis printf 동작 준수 - NUL에서 끊김, 길이 제한
 */
public final class Errors {

    /** 에러 문구 속 사용자 입력 최대 바이트 수 - Redis와 동일 */
    private static final int ECHOED_INPUT_LIMIT = 128;

    private Errors() {
    }

    /**
     * Redis 7 형식
     * 이름 - 대소문자 유지, 128바이트까지
     * 인자 - 목록 전체 128바이트 초과 전까지
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

    /** 하위 명령은 받은 그대로, 명령 이름은 대문자 */
    public static RespValue.Err unknownSubcommand(String subcommand, String commandName) {
        return new RespValue.Err(newlinesToSpaces("ERR unknown subcommand '" + subcommand + "'. Try "
                + commandName.toUpperCase(Locale.ROOT) + " HELP."));
    }

    public static RespValue.Err syntaxError() {
        return new RespValue.Err("ERR syntax error");
    }

    public static RespValue.Err wrongType() {
        return new RespValue.Err("WRONGTYPE Operation against a key holding the wrong kind of value");
    }

    public static RespValue.Err notAnInteger() {
        return new RespValue.Err("ERR value is not an integer or out of range");
    }

    /** 개수 인자가 음수 또는 비정수 */
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

    /** EXPIRE 계열의 모르는 옵션 */
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

    /** NUL에서 끊고 limit 바이트까지 절단 */
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
