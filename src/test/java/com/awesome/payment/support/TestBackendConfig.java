package com.awesome.payment.support;

import com.awesome.payment.config.ClockConfig;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;

/**
 * Replaces the HTTP backend client with {@link FakeBackend} and the system clock with a
 * {@link MutableClock} (each the only primary bean of its type). The service sees the mutable
 * clock through the production microsecond truncation, like the system clock in production.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestBackendConfig {

    public static final String WEBHOOK_SECRET = "test-webhook-secret";

    @Bean
    MutableClock testClock() {
        return new MutableClock();
    }

    @Bean
    @Primary
    Clock testPaymentClock(MutableClock testClock) {
        return ClockConfig.microsecondClock(testClock);
    }

    @Bean
    @Primary
    FakeBackend fakeBackend(MutableClock testClock) {
        return new FakeBackend(WEBHOOK_SECRET, testClock);
    }
}
