package com.awesome.payment.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Runs the simulated provider settlement and the result outbox retries. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfig {
}
