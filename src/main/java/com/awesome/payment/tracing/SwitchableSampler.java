package com.awesome.payment.tracing;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;

import java.util.List;

/**
 * The runtime switch of tracing (lab contract section 4). Spring AOT fixes Spring Boot's tracing
 * bean conditions when the native image is built, so tracing is always compiled in and this
 * sampler decides at runtime:
 * <ul>
 *   <li>{@code TRACING_ENABLED=false} (the default): nothing is sampled, so nothing is exported,
 *       even when the caller's {@code traceparent} says "sampled".</li>
 *   <li>{@code TRACING_ENABLED=true}: parent-based, with {@code TRACING_SAMPLING_PROBABILITY} for
 *       new traces, except that an outgoing call made outside any traced work (the event feed
 *       poll, the startup JWKS fetch) never starts a trace of its own.</li>
 * </ul>
 */
public final class SwitchableSampler implements Sampler {

    private final boolean enabled;
    private final Sampler delegate;

    public SwitchableSampler(boolean enabled, double probability) {
        this.enabled = enabled;
        this.delegate = Sampler.parentBased(Sampler.traceIdRatioBased(probability));
    }

    @Override
    public SamplingResult shouldSample(Context parentContext, String traceId, String name, SpanKind spanKind,
                                       Attributes attributes, List<LinkData> parentLinks) {
        if (!enabled) {
            return SamplingResult.drop();
        }
        if (spanKind == SpanKind.CLIENT && !Span.fromContext(parentContext).getSpanContext().isValid()) {
            return SamplingResult.drop();
        }
        return delegate.shouldSample(parentContext, traceId, name, spanKind, attributes, parentLinks);
    }

    @Override
    public String getDescription() {
        return enabled ? "SwitchableSampler{" + delegate.getDescription() + ", no root client spans}"
                : "SwitchableSampler{off}";
    }
}
