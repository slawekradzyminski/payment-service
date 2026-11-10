package com.awesome.payment.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    /**
     * Server-local clock used for payment, outbox and settlement timestamps and card expiry checks,
     * matching the server-local {@code LocalDateTime}
     * values exposed by the REST API (same convention as the backend).
     */
    @Bean
    Clock paymentClock() {
        return Clock.systemDefaultZone();
    }
}
