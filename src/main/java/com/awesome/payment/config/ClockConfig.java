package com.awesome.payment.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    /** PostgreSQL {@code timestamp(6)} precision. */
    static final Duration STORED_PRECISION = Duration.ofNanos(1_000);

    /**
     * Server-local clock used for payment, outbox and settlement timestamps and card expiry checks,
     * matching the server-local {@code LocalDateTime}
     * values exposed by the REST API (same convention as the backend).
     */
    @Bean
    Clock paymentClock() {
        return microsecondClock(Clock.systemDefaultZone());
    }

    /**
     * {@code base} truncated to whole microseconds, the precision the database keeps, so a timestamp
     * in a response (for example the 201 of a new payment) equals the stored one that every later
     * read returns.
     */
    public static Clock microsecondClock(Clock base) {
        return Clock.tick(base, STORED_PRECISION);
    }
}
