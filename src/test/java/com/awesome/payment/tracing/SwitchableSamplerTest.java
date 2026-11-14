package com.awesome.payment.tracing;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SwitchableSamplerTest {

    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final Context ROOT = Context.root();
    private static final Context SAMPLED_PARENT = parent(TraceFlags.getSampled());
    private static final Context UNSAMPLED_PARENT = parent(TraceFlags.getDefault());

    @ParameterizedTest
    @EnumSource(SpanKind.class)
    void offSamplesNothingNotEvenUnderASampledCaller(SpanKind kind) {
        SwitchableSampler off = new SwitchableSampler(false, 1.0);

        assertThat(decision(off, SAMPLED_PARENT, kind)).isEqualTo(SamplingDecision.DROP);
        assertThat(decision(off, ROOT, kind)).isEqualTo(SamplingDecision.DROP);
        assertThat(off.getDescription()).isEqualTo("SwitchableSampler{off}");
    }

    @ParameterizedTest
    @EnumSource(value = SpanKind.class, names = "CLIENT", mode = EnumSource.Mode.EXCLUDE)
    void onSamplesNewTracesWithTheProbability(SpanKind kind) {
        assertThat(decision(new SwitchableSampler(true, 1.0), ROOT, kind)).isEqualTo(SamplingDecision.RECORD_AND_SAMPLE);
        assertThat(decision(new SwitchableSampler(true, 0.0), ROOT, kind)).isEqualTo(SamplingDecision.DROP);
    }

    @Test
    void onFollowsTheParentsDecision() {
        SwitchableSampler never = new SwitchableSampler(true, 0.0);
        SwitchableSampler always = new SwitchableSampler(true, 1.0);

        assertThat(decision(never, SAMPLED_PARENT, SpanKind.SERVER)).isEqualTo(SamplingDecision.RECORD_AND_SAMPLE);
        assertThat(decision(never, SAMPLED_PARENT, SpanKind.CLIENT)).isEqualTo(SamplingDecision.RECORD_AND_SAMPLE);
        assertThat(decision(always, UNSAMPLED_PARENT, SpanKind.SERVER)).isEqualTo(SamplingDecision.DROP);
        assertThat(always.getDescription()).startsWith("SwitchableSampler{ParentBased{").endsWith("no root client spans}");
    }

    @Test
    void onNeverStartsATraceWithAnOutgoingCall() {
        assertThat(decision(new SwitchableSampler(true, 1.0), ROOT, SpanKind.CLIENT)).isEqualTo(SamplingDecision.DROP);
    }

    private static SamplingDecision decision(SwitchableSampler sampler, Context parent, SpanKind kind) {
        return sampler.shouldSample(parent, TRACE, "name", kind, Attributes.empty(), List.of()).getDecision();
    }

    private static Context parent(TraceFlags flags) {
        return Context.root().with(Span.wrap(SpanContext.createFromRemoteParent(TRACE, "00f067aa0ba902b7", flags,
                TraceState.getDefault())));
    }
}
