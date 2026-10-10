package io.github.backaad.mobilemoney.testing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock that tests move forward by hand. */
public final class MutableClock extends Clock {

    private Instant now;

    public MutableClock(Instant start) {
        this.now = start;
    }

    public static MutableClock at(String isoInstant) {
        return new MutableClock(Instant.parse(isoInstant));
    }

    public synchronized void advance(Duration duration) {
        now = now.plus(duration);
    }

    @Override
    public synchronized Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
