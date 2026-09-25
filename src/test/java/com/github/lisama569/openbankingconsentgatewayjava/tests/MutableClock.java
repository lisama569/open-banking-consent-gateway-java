package com.github.lisama569.openbankingconsentgatewayjava.tests;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/** 测试用可变时钟。 */
public class MutableClock extends Clock {
    private Instant instant;
    private final ZoneId zone;

    public MutableClock(Instant instant) {
        this.instant = instant;
        this.zone = ZoneId.of("UTC");
    }

    public void advanceSeconds(long seconds) {
        this.instant = this.instant.plusSeconds(seconds);
    }

    @Override
    public ZoneId getZone() { return zone; }

    @Override
    public Clock withZone(ZoneId zone) { return this; }

    @Override
    public Instant instant() { return instant; }
}
