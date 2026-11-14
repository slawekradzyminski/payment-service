package com.awesome.payment.correlation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationTest {

    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String PARENT = "00f067aa0ba902b7";

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "gw-123", "A.b_c-9", "2f1b8c9e-6f0e-4a8e-9a71-0d4f1d7e2a10"})
    void validRequestIdsAreKept(String requestId) {
        assertThat(Correlation.validRequestId(requestId)).isEqualTo(requestId);
    }

    @Test
    void aRequestIdOf128CharactersIsKeptAnd129AreNot() {
        assertThat(Correlation.validRequestId("x".repeat(128))).hasSize(128);
        assertThat(Correlation.validRequestId("x".repeat(129))).isNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"has space", "semi;colon", "new\nline", "ünicode", "a/b", "<script>"})
    void invalidRequestIdsAreRejected(String requestId) {
        assertThat(Correlation.validRequestId(requestId)).isNull();
    }

    @Test
    void aValidTraceparentIsParsed() {
        assertThat(Correlation.parseTraceparent("00-" + TRACE + "-" + PARENT + "-01"))
                .isEqualTo(new Correlation.TraceParent(TRACE, PARENT, "01"));
        assertThat(Correlation.parseTraceparent("00-" + TRACE + "-" + PARENT + "-00").flags()).isEqualTo("00");
        assertThat(Correlation.traceIdOf("00-" + TRACE + "-" + PARENT + "-01")).isEqualTo(TRACE);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
        "00-00000000000000000000000000000000-00f067aa0ba902b7-01",
        "00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01",
        "01-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
        "00-4BF92F3577B34DA6A3CE929D0E0E4736-00f067aa0ba902b7-01",
        "00-4bf92f3577b34da6a3ce929d0e0e473-00f067aa0ba902b7-01",
        "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b-01",
        "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-1",
        "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01-extra",
        "garbage"
    })
    void invalidTraceparentsAreRejected(String traceparent) {
        assertThat(Correlation.parseTraceparent(traceparent)).isNull();
        assertThat(Correlation.traceIdOf(traceparent)).isNull();
    }

    @Test
    void traceIdsMustBe32LowerCaseHexAndNotAllZeros() {
        assertThat(Correlation.validTraceId(TRACE)).isEqualTo(TRACE);
        assertThat(Correlation.validTraceId(null)).isNull();
        assertThat(Correlation.validTraceId("0".repeat(32))).isNull();
        assertThat(Correlation.validTraceId(TRACE.toUpperCase(java.util.Locale.ROOT))).isNull();
        assertThat(Correlation.validTraceId(TRACE + "0")).isNull();
    }

    @Test
    void spanIdsMustBe16LowerCaseHexAndNotAllZeros() {
        assertThat(Correlation.validSpanId("00f067aa0ba902b7")).isEqualTo("00f067aa0ba902b7");
        assertThat(Correlation.validSpanId(null)).isNull();
        assertThat(Correlation.validSpanId("0".repeat(16))).isNull();
        assertThat(Correlation.validSpanId("00F067AA0BA902B7")).isNull();
        assertThat(Correlation.validSpanId("00f067aa0ba902b70")).isNull();
        assertThat(Correlation.validSpanId("00f067aa0ba902b")).isNull();
    }

    @Test
    void generatedIdsAreValidAndDistinct() {
        Set<String> traces = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            String traceId = Correlation.newTraceId();
            String spanId = Correlation.newSpanId();
            String requestId = Correlation.newRequestId();
            assertThat(Correlation.validTraceId(traceId)).isNotNull();
            assertThat(spanId).matches("^[0-9a-f]{16}$").isNotEqualTo("0".repeat(16));
            assertThat(Correlation.validRequestId(requestId)).isNotNull();
            assertThat(Correlation.parseTraceparent(Correlation.traceparent(traceId, spanId)))
                    .isEqualTo(new Correlation.TraceParent(traceId, spanId, "01"));
            traces.add(traceId);
        }
        assertThat(traces).hasSize(50);
    }

    @Test
    void aScopeSetsTheIdsWithANewSpanAndRestoresThePreviousOnes() {
        assertThat(Correlation.currentTraceparent()).isNull();
        try (Correlation.Scope outer = Correlation.open("outer", TRACE)) {
            String outerSpan = MDC.get(Correlation.SPAN_ID);
            assertThat(outerSpan).matches("^[0-9a-f]{16}$");
            assertThat(Correlation.currentRequestId()).isEqualTo("outer");
            assertThat(Correlation.currentTraceId()).isEqualTo(TRACE);
            assertThat(Correlation.currentTraceparent()).isEqualTo("00-" + TRACE + "-" + outerSpan + "-01");

            try (Correlation.Scope inner = Correlation.open("inner", null)) {
                assertThat(Correlation.currentRequestId()).isEqualTo("inner");
                assertThat(Correlation.currentTraceId()).isNull();
                assertThat(MDC.get(Correlation.SPAN_ID)).isNull();
                assertThat(Correlation.currentTraceparent()).isNull();
            }

            assertThat(Correlation.currentRequestId()).isEqualTo("outer");
            assertThat(MDC.get(Correlation.SPAN_ID)).isEqualTo(outerSpan);
        }
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void aTraceWithoutSpanHasNoTraceparent() {
        MDC.put(Correlation.TRACE_ID, TRACE);

        assertThat(Correlation.currentTraceparent()).isNull();
    }
}
