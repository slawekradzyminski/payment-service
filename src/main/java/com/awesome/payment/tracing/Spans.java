package com.awesome.payment.tracing;

import com.awesome.payment.correlation.Correlation;
import io.micrometer.tracing.Link;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Spans for the work this service does outside an HTTP request (lab contract section 4): the
 * feed consumer, the result outbox dispatcher and the settlement job. HTTP server spans and the
 * spans of calls to the backend come from Spring Boot's instrumentation.
 *
 * <p>Every method opens the {@link Correlation} scope the work ran under before tracing existed
 * ({@code requestId} in the MDC, restored on close) and makes a span current, so the MDC
 * {@code traceId}/{@code spanId} (set by Micrometer Tracing), the stored {@code traceparent} of
 * anything written and the {@code traceparent} sent to the backend are this span's. With tracing off
 * ({@code TRACING_ENABLED=false}, the default) the span is not sampled, so nothing is recorded or
 * exported, but it still carries the trace id: the instrumented {@code RestClient} opens its own
 * (unsampled) span around each call, whose trace must stay the work's trace for the hand-built
 * {@code traceparent} and the MDC of 1.2.
 */
@Component
public class Spans {

    private final Tracer tracer;
    private final boolean tracingEnabled;

    public Spans(Tracer tracer, @Value("${tracing.enabled:false}") boolean enabled) {
        this.tracer = tracer;
        this.tracingEnabled = enabled;
    }

    /** Tracing off with a no-op tracer: only the correlation scopes (unit tests). */
    public static Spans disabled() {
        return new Spans(Tracer.NOOP, false);
    }

    /** Whether tracing is switched on ({@code TRACING_ENABLED}). */
    public boolean enabled() {
        return tracingEnabled;
    }

    /**
     * A span of {@code kind} (null: internal) whose parent is {@code traceId}/{@code parentSpanId}
     * (a remote parent, for example a feed event's or a stored {@code traceparent}'s ids). A valid
     * trace id without a span id still keeps the trace: the parent span id is then made up, so
     * tracing UIs show the parent as missing. Without a valid trace id the span starts a new trace
     * (tracing off: no span at all, so the MDC has no trace id, as in 1.2).
     * The MDC gets {@code requestId}; invalid ids must be filtered out by the caller.
     */
    public Scope child(String name, Span.Kind kind, String requestId, String traceId, String parentSpanId) {
        Correlation.Scope correlation = Correlation.open(requestId, traceId);
        if (!tracingEnabled && traceId == null) {
            // Nothing to carry: like 1.2, the work runs without a trace id in the MDC.
            return new Scope(correlation, null, null);
        }
        Span.Builder builder = tracer.spanBuilder().name(name);
        if (kind != null) {
            builder.kind(kind);
        }
        if (traceId == null) {
            builder.setNoParent();
        } else {
            builder.setParent(context(traceId, parentSpanId == null ? Correlation.newSpanId() : parentSpanId));
        }
        return started(correlation, builder);
    }

    /**
     * An internal span that starts a new trace, linked to the span of {@code originTraceparent}
     * (when valid). With tracing off it is an (unsampled) child of the origin instead, so the logs
     * keep the origin's trace id, as in 1.2.
     */
    public Scope root(String name, String requestId, String originTraceparent) {
        Correlation.TraceParent origin = Correlation.parseTraceparent(originTraceparent);
        if (!tracingEnabled) {
            return child(name, null, requestId, origin == null ? null : origin.traceId(),
                    origin == null ? null : origin.parentId());
        }
        Correlation.Scope correlation = Correlation.open(requestId, null);
        Span.Builder builder = tracer.spanBuilder().name(name).setNoParent();
        if (origin != null) {
            builder.addLink(new Link(context(origin.traceId(), origin.parentId())));
        }
        return started(correlation, builder);
    }

    private Scope started(Correlation.Scope correlation, Span.Builder builder) {
        Span span = builder.start();
        return new Scope(correlation, span, tracer.withSpan(span));
    }

    private TraceContext context(String traceId, String spanId) {
        return tracer.traceContextBuilder().traceId(traceId).spanId(spanId).sampled(true).build();
    }

    /** Ends the span (if any) and restores the MDC; tags and errors are ignored without a span. */
    public static final class Scope implements AutoCloseable {

        private final Correlation.Scope correlation;
        private final Span span;
        private final Tracer.SpanInScope inScope;

        private Scope(Correlation.Scope correlation, Span span, Tracer.SpanInScope inScope) {
            this.correlation = correlation;
            this.span = span;
            this.inScope = inScope;
        }

        /** Adds a span attribute; null values are left out. */
        public Scope tag(String key, Object value) {
            if (span != null && value != null) {
                span.tag(key, String.valueOf(value));
            }
            return this;
        }

        /** Marks the span as failed. */
        public void error(Throwable failure) {
            if (span != null) {
                span.error(failure);
            }
        }

        @Override
        public void close() {
            try (Correlation.Scope ignored = correlation) {
                if (span != null) {
                    inScope.close();
                    span.end();
                }
            }
        }
    }
}
