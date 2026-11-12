package glassredis.command;

/**
 * glob 패턴 매칭 - util.c stringmatchlen 이식 (KEYS, 이후 SCAN MATCH 공용)
 * - 지원 문법: * ? [abc] [^abc] [a-z] \x
 * - 바이트 단위 비교, byte 는 C char 처럼 부호 있음 - 범위 비교 결과 일치
 * - 패턴 끝 너머 읽기: C 는 sds 끝 NUL 을 읽음 → at() 이 0 반환으로 재현
 */
public final class StringMatch {

    private StringMatch() {
    }

    public static boolean matches(byte[] pattern, byte[] string, boolean nocase) {
        Matcher matcher = new Matcher(pattern, string, nocase);
        return matcher.match(0, pattern.length, 0, string.length);
    }

    /** skipLongerMatches - 한 번 실패한 뒤 앞쪽 * 의 더 긴 매칭 시도 생략, 지수 시간 방지 */
    private static final class Matcher {
        private final byte[] pattern;
        private final byte[] string;
        private final boolean nocase;
        private boolean skipLongerMatches;

        Matcher(byte[] pattern, byte[] string, boolean nocase) {
            this.pattern = pattern;
            this.string = string;
            this.nocase = nocase;
        }

        /** p·s - 현재 위치, pl·sl - 남은 길이 (stringmatchlen_impl 의 포인터·길이 쌍) */
        boolean match(int p, int pl, int s, int sl) {
            while (pl > 0 && sl > 0) {
                switch (at(p)) {
                    case '*' -> {
                        // 연속 * 은 하나로
                        while (pl > 0 && at(p + 1) == '*') {
                            p++;
                            pl--;
                        }
                        if (pl == 1) {
                            return true;
                        }
                        // 나머지 패턴을 문자열의 모든 시작 위치에 대입
                        while (sl > 0) {
                            if (match(p + 1, pl - 1, s, sl)) {
                                return true;
                            }
                            if (skipLongerMatches) {
                                return false;
                            }
                            s++;
                            sl--;
                        }
                        // 어디서 시작해도 실패 - 앞쪽 * 을 늘려도 소용없음
                        skipLongerMatches = true;
                        return false;
                    }
                    case '?' -> {
                        s++;
                        sl--;
                    }
                    case '[' -> {
                        p++;
                        pl--;
                        boolean not = at(p) == '^';
                        if (not) {
                            p++;
                            pl--;
                        }
                        boolean match = false;
                        while (true) {
                            if (at(p) == '\\' && pl >= 2) {
                                p++;
                                pl--;
                                if (at(p) == string[s]) {
                                    match = true;
                                }
                            } else if (at(p) == ']') {
                                break;
                            } else if (pl == 0) {
                                // 닫는 ] 없음 - 바깥 루프의 p++ 상쇄용으로 한 칸 되돌림
                                p--;
                                pl++;
                                break;
                            } else if (pl >= 3 && at(p + 1) == '-') {
                                int start = at(p);
                                int end = at(p + 2);
                                int c = string[s];
                                if (start > end) {
                                    int t = start;
                                    start = end;
                                    end = t;
                                }
                                if (nocase) {
                                    start = lower(start);
                                    end = lower(end);
                                    c = lower(c);
                                }
                                p += 2;
                                pl -= 2;
                                if (c >= start && c <= end) {
                                    match = true;
                                }
                            } else if (same(at(p), string[s])) {
                                match = true;
                            }
                            p++;
                            pl--;
                        }
                        if (not) {
                            match = !match;
                        }
                        if (!match) {
                            return false;
                        }
                        s++;
                        sl--;
                    }
                    default -> {
                        // \x - 다음 글자를 문자 그대로, 패턴 끝의 \ 는 \ 자신
                        if (at(p) == '\\' && pl >= 2) {
                            p++;
                            pl--;
                        }
                        if (!same(at(p), string[s])) {
                            return false;
                        }
                        s++;
                        sl--;
                    }
                }
                p++;
                pl--;
                if (sl == 0) {
                    // 문자열 소진 - 남은 * 은 빈 문자열과 매칭
                    while (at(p) == '*') {
                        p++;
                        pl--;
                    }
                    break;
                }
            }
            return pl == 0 && sl == 0;
        }

        /** 패턴 끝 너머는 0 - sds 의 NUL 종결자 */
        private int at(int i) {
            return i < pattern.length ? pattern[i] : 0;
        }

        private boolean same(int a, int b) {
            return nocase ? lower(a) == lower(b) : a == b;
        }

        /** C tolower - ASCII 대문자만 변환 */
        private static int lower(int c) {
            return c >= 'A' && c <= 'Z' ? c + ('a' - 'A') : c;
        }
    }
}
