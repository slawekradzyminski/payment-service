package com.awesome.payment.tracing;

import com.awesome.payment.correlation.Correlation;
import com.awesome.payment.support.TracingKit;
import io.micrometer.tracing.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

class SpansTest {

    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String PARENT = "00f067aa0ba902b7";

    private final TracingKit kit = new TracingKit(true);
    private final Spans spans = new Spans(kit.tracer, true);

    @AfterEach
    void noCorrelationLeaks() {
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void aChildSpanIsParentedByTheGivenIdsAndCarriesItsKindNameAndTags() {
        try (Spans.Scope scope = spans.child("process card-refund.requested", Span.Kind.CONSUMER, "req-1", TRACE,
                PARENT)) {
            scope.tag("event.sequence", 7L).tag("ignored", null);
            assertThat(MDC.get(Correlation.REQUEST_ID)).isEqualTo("req-1");
        }

        assertThat(kit.exporter.spans()).singleElement().satisfies(span -> {
            assertThat(span.getName()).isEqualTo("process card-refund.requested");
            assertThat(span.getKind()).isEqualTo(SpanKind.CONSUMER);
            assertThat(span.getTraceId()).isEqualTo(TRACE);
            assertThat(span.getParentSpanId()).isEqualTo(PARENT);
            assertThat(span.getAttributes().asMap()).hasSize(1);
            assertThat(span.getAttributes().asMap().values()).containsExactly("7");
            assertThat(span.hasEnded()).isTrue();
        });
    }

    @Test
    void aTraceIdWithoutSpanIdKeepsTheTraceUnderAMadeUpParent() {
        try (Spans.Scope ignored = spans.child("process x", Span.Kind.CONSUMER, null, TRACE, null)) {
            assertThat(MDC.get(Correlation.REQUEST_ID)).isNull();
        }

        SpanData span = kit.exporter.spans().getFirst();
        assertThat(span.getTraceId()).isEqualTo(TRACE);
        assertThat(span.getParentSpanContext().isValid()).isTrue();
    }

    @Test
    void withoutATraceIdTheSpanStartsANewTraceEvenInsideAnotherSpan() {
        try (Spans.Scope outer = spans.root("outer", null, null);
             Spans.Scope inner = spans.child("dispatch", Span.Kind.PRODUCER, null, null, null)) {
            inner.error(new IllegalStateException("boom"));
        }

        SpanData outer = kit.exporter.named("outer").getFirst();
        SpanData inner = kit.exporter.named("dispatch").getFirst();
        assertThat(inner.getParentSpanContext().isValid()).isFalse();
        assertThat(inner.getTraceId()).isNotEqualTo(outer.getTraceId());
        assertThat(inner.getKind()).isEqualTo(SpanKind.PRODUCER);
        assertThat(inner.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
    }

    @Test
    void aRootSpanStartsANewTraceLinkedToItsOrigin() {
        try (Spans.Scope ignored = spans.root("settle payment", "req-2", Correlation.traceparent(TRACE, PARENT))) {
            assertThat(MDC.get(Correlation.REQUEST_ID)).isEqualTo("req-2");
        }

        assertThat(kit.exporter.spans()).singleElement().satisfies(span -> {
            assertThat(span.getKind()).isEqualTo(SpanKind.INTERNAL);
            assertThat(span.getParentSpanContext().isValid()).isFalse();
            assertThat(span.getTraceId()).isNotEqualTo(TRACE);
            assertThat(span.getLinks()).singleElement().satisfies(link -> {
                assertThat(link.getSpanContext().getTraceId()).isEqualTo(TRACE);
                assertThat(link.getSpanContext().getSpanId()).isEqualTo(PARENT);
            });
        });
    }

    @Test
    void aRootSpanWithoutAValidOriginHasNoLink() {
        try (Spans.Scope ignored = spans.root("settle payment", null, "garbage")) {
            assertThat(MDC.get(Correlation.TRACE_ID)).isNull();
        }

        assertThat(kit.exporter.spans()).singleElement()
                .satisfies(span -> assertThat(span.getLinks()).isEmpty());
    }

    @Test
    void tracingOffRecordsNothingAndKeepsTheCorrelationOfBefore() {
        TracingKit offKit = new TracingKit(false);
        Spans off = new Spans(offKit.tracer, false);
        assertThat(off.enabled()).isFalse();
        assertThat(spans.enabled()).isTrue();

        try (Spans.Scope child = off.child("process x", Span.Kind.CONSUMER, "req-3", TRACE, PARENT)) {
            child.tag("k", "v").error(new IllegalStateException("ignored"));
            assertThat(MDC.get(Correlation.REQUEST_ID)).isEqualTo("req-3");
            assertThat(MDC.get(Correlation.TRACE_ID)).isEqualTo(TRACE);
            assertThat(MDC.get(Correlation.SPAN_ID)).matches("^[0-9a-f]{16}$").isNotEqualTo(PARENT);
        }
        try (Spans.Scope ignored = off.root("settle payment", "req-4", Correlation.traceparent(TRACE, PARENT))) {
            assertThat(MDC.get(Correlation.REQUEST_ID)).isEqualTo("req-4");
            assertThat(MDC.get(Correlation.TRACE_ID)).as("the origin's trace, as before tracing").isEqualTo(TRACE);
        }
        try (Spans.Scope ignored = Spans.disabled().root("settle payment", null, null)) {
            assertThat(MDC.get(Correlation.TRACE_ID)).isNull();
        }

        assertThat(offKit.exporter.spans()).isEmpty();
    }
}
