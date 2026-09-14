package glassredis.store;

import java.time.Instant;
import java.time.InstantSource;

/** 테스트용 시계. 손으로 돌리기 전에는 시간이 흐르지 않는다. */
public final class ManualClock implements InstantSource {

    private long millis;

    public ManualClock(long startMillis) {
        this.millis = startMillis;
    }

    @Override
    public Instant instant() {
        return Instant.ofEpochMilli(millis);
    }

    @Override
    public long millis() {
        return millis;
    }

    public void advanceMillis(long amount) {
        millis += amount;
    }
}
