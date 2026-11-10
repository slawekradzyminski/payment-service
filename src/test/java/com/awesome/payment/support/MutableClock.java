package com.awesome.payment.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock the tests move by hand (settlement delays, outbox backoff, card expiry). */
public class MutableClock extends Clock {

    public static final Instant START = Instant.parse("2026-11-10T12:00:00Z");

    private volatile Instant now = START;

    public void reset() {
        now = START;
    }

    public void advance(Duration duration) {
        now = now.plus(duration);
    }

    public void set(Instant instant) {
        now = instant;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
