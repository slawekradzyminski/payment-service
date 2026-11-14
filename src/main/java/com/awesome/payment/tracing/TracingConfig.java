package com.awesome.payment.tracing;

import io.micrometer.observation.ObservationPredicate;
import io.micrometer.tracing.otel.bridge.Slf4JEventListener;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Tracing wiring on top of Spring Boot's OpenTelemetry auto-configuration (which is always
 * active, see {@link SwitchableSampler}). The environment of the lab contract maps to:
 * <ul>
 *   <li>{@code TRACING_ENABLED} → {@code tracing.enabled} (this service's runtime switch)</li>
 *   <li>{@code TRACING_OTLP_ENDPOINT} → {@code management.opentelemetry.tracing.export.otlp.endpoint}</li>
 *   <li>{@code TRACING_SAMPLING_PROBABILITY} → {@code management.tracing.sampling.probability}</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class TracingConfig {

    /** Spring's observation of every {@code @Scheduled} run (settlement every 2 s, feed poll every 5 s). */
    static final String SCHEDULED_TASK_OBSERVATION = "tasks.scheduled.execution";
    /** Spring Security's observations (filter chain stages, authorization): five spans per request. */
    static final String SECURITY_OBSERVATIONS = "spring.security.";

    /** Replaces Spring Boot's sampler (which only reads its properties, not an on/off switch). */
    @Bean
    Sampler otelSampler(@Value("${tracing.enabled:false}") boolean enabled,
                        @Value("${management.tracing.sampling.probability:1.0}") double probability) {
        return new SwitchableSampler(enabled, probability);
    }

    /**
     * Leaves the MDC to the correlation code ({@code CorrelationFilter}, {@link Spans}), so the
     * {@code traceId}/{@code spanId} keys behave the same with tracing on and off and an
     * instrumented backend call never rewrites them (same choice as warehouse-service).
     */
    @Bean
    Slf4JEventListener otelSlf4JEventListener() {
        return new SilentMdcListener();
    }

    /** Ignores span scope events. */
    static final class SilentMdcListener extends Slf4JEventListener {

        @Override
        public void onEvent(Object event) {
            // The trace and span ids in the MDC are set by Correlation scopes.
        }
    }

    /**
     * No observation (and so no span or timer) for the scheduler ticks and for Spring Security's
     * internals. Most ticks find nothing to do and would be a trace every few seconds; the work they
     * find gets its own spans ({@link Spans}). The security spans would sit between the HTTP server
     * span and everything the request does. None of these timers is used by a dashboard; the HTTP
     * server and client timers stay.
     */
    @Bean
    ObservationPredicate noSchedulerOrSecurityObservations() {
        return (name, context) -> !SCHEDULED_TASK_OBSERVATION.equals(name) && !name.startsWith(SECURITY_OBSERVATIONS);
    }
}
