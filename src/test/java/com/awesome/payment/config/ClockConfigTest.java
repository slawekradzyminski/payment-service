package com.awesome.payment.config;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class ClockConfigTest {

    @Test
    void theMicrosecondClockDropsWhatTheDatabaseCannotStore() {
        Clock base = Clock.fixed(Instant.parse("2026-11-12T07:58:13.987074842Z"), ZoneOffset.UTC);

        Clock clock = ClockConfig.microsecondClock(base);

        assertThat(clock.instant()).isEqualTo(Instant.parse("2026-11-12T07:58:13.987074Z"));
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    void thePaymentClockKeepsMicrosecondsAtMost() {
        Clock clock = new ClockConfig().paymentClock();

        for (int i = 0; i < 100; i++) {
            assertThat(clock.instant().getNano() % 1_000).isZero();
        }
        assertThat(clock.getZone()).isEqualTo(Clock.systemDefaultZone().getZone());
    }
}
