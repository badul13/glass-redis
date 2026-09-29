package glassredis.store;

import java.util.Objects;

/**
 * 값과 만료 시각 - 만료 시각은 에포크 기준 절대 ms
 * Entry는 불변, 모음 자료형 Value는 제자리 변경
 */
public record Entry(Value value, long expireAtMillis) {

    public static final long NO_EXPIRY = -1;

    public Entry {
        Objects.requireNonNull(value, "value");
        if (expireAtMillis < 0 && expireAtMillis != NO_EXPIRY) {
            throw new IllegalArgumentException("만료 시각이 음수입니다: " + expireAtMillis);
        }
    }

    public static Entry of(Value value) {
        return new Entry(value, NO_EXPIRY);
    }

    public static Entry of(byte[] value) {
        return of(StringValue.of(value));
    }

    public boolean hasExpiry() {
        return expireAtMillis != NO_EXPIRY;
    }

    /** 만료 시각과 같은 ms에는 생존 - Redis와 동일하게 now > when */
    public boolean isExpiredAt(long nowMillis) {
        return hasExpiry() && nowMillis > expireAtMillis;
    }

    /** 만료 시각 유지 - INCR, APPEND는 만료 보존 */
    public Entry withValue(Value newValue) {
        return new Entry(newValue, expireAtMillis);
    }

    public Entry withExpireAt(long newExpireAtMillis) {
        return new Entry(value, newExpireAtMillis);
    }
}
