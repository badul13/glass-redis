package glassredis.store;

import java.util.Objects;

/**
 * 키스페이스에 저장된 값 하나와 그 만료 시각.
 *
 * <p>값을 바꿀 때는 이 객체를 고치지 않고 새 {@code Entry} 로 갈아 끼운다.
 *
 * <p>만료 시각은 "지금부터 몇 ms 뒤"가 아니라 유닉스 에포크 기준 절대 시각(ms)으로 들고 있다.
 * 상대 시간으로 들고 있으면 시간이 흐를 때마다 줄여줘야 하고, 나중에 AOF 로 디스크에 적었다가
 * 재시작 후 읽을 때 "언제부터 10초였는지"를 잃어버린다.
 */
public record Entry(byte[] value, long expireAtMillis) {

    /** 만료 시각이 없음을 나타내는 값. */
    public static final long NO_EXPIRY = -1;

    public Entry {
        Objects.requireNonNull(value, "value");
        if (expireAtMillis < 0 && expireAtMillis != NO_EXPIRY) {
            throw new IllegalArgumentException("만료 시각이 음수입니다: " + expireAtMillis);
        }
    }

    /** 만료 시각이 없는 값. */
    public static Entry of(byte[] value) {
        return new Entry(value, NO_EXPIRY);
    }

    public boolean hasExpiry() {
        return expireAtMillis != NO_EXPIRY;
    }

    /**
     * 주어진 시각에 이미 만료됐는지.
     *
     * <p>만료 시각과 정확히 같은 ms 에는 아직 살아 있다. 실제 Redis 도 {@code now > when} 으로 판단한다.
     */
    public boolean isExpiredAt(long nowMillis) {
        return hasExpiry() && nowMillis > expireAtMillis;
    }

    /** 만료 시각은 그대로 두고 값만 바꾼다. INCR, APPEND 처럼 값을 고치는 명령은 만료 시각을 지우지 않는다. */
    public Entry withValue(byte[] newValue) {
        return new Entry(newValue, expireAtMillis);
    }

    public Entry withExpireAt(long newExpireAtMillis) {
        return new Entry(value, newExpireAtMillis);
    }
}
