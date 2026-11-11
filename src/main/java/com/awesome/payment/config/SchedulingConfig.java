package com.awesome.payment.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Runs the simulated provider settlement, the result outbox retries and the event feed poller. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfig {
}
