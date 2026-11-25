package glassredis.command;

/**
 * 명령 옵션 단어 비교 - strcasecmp(argv[j]->ptr, "nx") 와 같은 규칙
 * - ASCII 대소문자 무관 (C 로캘)
 * - 인자는 NUL 에서 끝나는 C 문자열 - "NX\0abc" 도 NX
 * 옵션 비교는 전부 여기로 - 문자셋 변환 후 equals 금지
 */
public final class Options {

    private Options() {
    }

    /** @param option 대문자 ASCII 옵션 이름 */
    public static boolean is(byte[] arg, String option) {
        int length = option.length();
        for (int i = 0; i < length; i++) {
            if (i >= arg.length || arg[i] == 0 || upper(arg[i]) != option.charAt(i)) {
                return false;
            }
        }
        return arg.length == length || arg[length] == 0;
    }

    private static int upper(byte b) {
        return b >= 'a' && b <= 'z' ? b - 32 : b & 0xFF;
    }
}
