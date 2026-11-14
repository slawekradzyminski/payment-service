package com.awesome.payment.support;

import com.awesome.payment.tracing.SwitchableSampler;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.handler.PropagatingReceiverTracingObservationHandler;
import io.micrometer.tracing.handler.PropagatingSenderTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.OtelBaggageManager;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

import java.util.List;

/**
 * A real OpenTelemetry SDK behind Micrometer Tracing, wired like Spring Boot does it (W3C
 * propagation, {@link SwitchableSampler}), exporting synchronously to a {@link RecordingSpanExporter}.
 */
public final class TracingKit {

    public final RecordingSpanExporter exporter = new RecordingSpanExporter();
    public final OtelTracer tracer;
    public final ObservationRegistry observationRegistry = ObservationRegistry.create();

    public TracingKit(boolean enabled) {
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .setSampler(new SwitchableSampler(enabled, 1.0))
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        OtelCurrentTraceContext context = new OtelCurrentTraceContext();
        tracer = new OtelTracer(provider.get("test"), context, event -> { },
                new OtelBaggageManager(context, List.of(), List.of()));
        OtelPropagator propagator = new OtelPropagator(
                ContextPropagators.create(W3CTraceContextPropagator.getInstance()), provider.get("test"));
        observationRegistry.observationConfig().observationHandler(
                new ObservationHandler.FirstMatchingCompositeObservationHandler(
                        new PropagatingSenderTracingObservationHandler<>(tracer, propagator),
                        new PropagatingReceiverTracingObservationHandler<>(tracer, propagator),
                        new DefaultTracingObservationHandler(tracer)));
    }
}
