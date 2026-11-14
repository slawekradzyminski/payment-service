package com.awesome.payment.tracing;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TracingConfigTest {

    private final TracingConfig config = new TracingConfig();

    @Test
    void theSamplerFollowsTheRuntimeSwitch() {
        assertThat(config.otelSampler(false, 1.0).getDescription()).isEqualTo("SwitchableSampler{off}");
        assertThat(config.otelSampler(true, 0.25).getDescription()).contains("TraceIdRatioBased{0.250000}");
    }

    @Test
    void micrometersMdcListenerIsSilencedSoTheCorrelationCodeOwnsTheMdc() {
        io.micrometer.tracing.otel.bridge.Slf4JEventListener listener = config.otelSlf4JEventListener();

        assertThat(listener).isInstanceOf(TracingConfig.SilentMdcListener.class);
        listener.onEvent(new Object());
        assertThat(org.slf4j.MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void schedulerTicksAndSpringSecurityAreNotObservedButHttpIs() {
        ObservationPredicate predicate = config.noSchedulerOrSecurityObservations();
        Observation.Context context = new Observation.Context();

        assertThat(predicate.test("tasks.scheduled.execution", context)).isFalse();
        assertThat(predicate.test("spring.security.filterchains", context)).isFalse();
        assertThat(predicate.test("spring.security.authorizations", context)).isFalse();
        assertThat(predicate.test("http.server.requests", context)).isTrue();
        assertThat(predicate.test("http.client.requests", context)).isTrue();
        assertThat(predicate.test("tasks.scheduled.execution.other", context)).isTrue();
    }
}
