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
 * <p>The MDC belongs to the correlation code (Micrometer's MDC listener is switched off, see
 * {@link TracingConfig}). Every method opens a {@link Correlation} scope, restored on close. With
 * tracing off ({@code TRACING_ENABLED=false}, the default) that is all, exactly as in 1.2: no span
 * is started. With tracing on, a span is started and made current, and the MDC gets its trace and
 * span id, so the logs, the stored {@code traceparent} of anything written and the
 * {@code traceparent} sent to the backend are this span's.
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
     * tracing UIs show the parent as missing. Without a valid trace id the span starts a new trace.
     * Tracing off: only {@code Correlation.open(requestId, traceId)}. Invalid ids must be filtered
     * out by the caller.
     */
    public Scope child(String name, Span.Kind kind, String requestId, String traceId, String parentSpanId) {
        if (!tracingEnabled) {
            return new Scope(Correlation.open(requestId, traceId), null, null);
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
        return started(requestId, builder);
    }

    /**
     * An internal span that starts a new trace, linked to the span of {@code originTraceparent}
     * (when valid). Tracing off: the MDC keeps the origin's trace id, as in 1.2.
     */
    public Scope root(String name, String requestId, String originTraceparent) {
        Correlation.TraceParent origin = Correlation.parseTraceparent(originTraceparent);
        if (!tracingEnabled) {
            return new Scope(Correlation.open(requestId, origin == null ? null : origin.traceId()), null, null);
        }
        Span.Builder builder = tracer.spanBuilder().name(name).setNoParent();
        if (origin != null) {
            builder.addLink(new Link(context(origin.traceId(), origin.parentId())));
        }
        return started(requestId, builder);
    }

    /** The current span's ids with tracing on (in a request: the HTTP server span), otherwise null. */
    public TraceContext current() {
        if (!tracingEnabled) {
            return null;
        }
        Span span = tracer.currentSpan();
        return span == null ? null : span.context();
    }

    private Scope started(String requestId, Span.Builder builder) {
        Span span = builder.start();
        Correlation.Scope correlation = Correlation.open(requestId, span.context().traceId(),
                span.context().spanId());
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
