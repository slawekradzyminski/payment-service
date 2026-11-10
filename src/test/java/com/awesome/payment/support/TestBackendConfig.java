package com.awesome.payment.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the HTTP backend client with {@link FakeBackend} and the system clock with a
 * {@link MutableClock} (each the only primary bean of its type).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestBackendConfig {

    public static final String WEBHOOK_SECRET = "test-webhook-secret";

    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock();
    }

    @Bean
    @Primary
    FakeBackend fakeBackend(MutableClock testClock) {
        return new FakeBackend(WEBHOOK_SECRET, testClock);
    }
}
