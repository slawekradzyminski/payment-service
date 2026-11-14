package com.awesome.payment.endpoints;

import com.awesome.payment.client.BackendClient;
import com.awesome.payment.correlation.Correlation;
import com.awesome.payment.support.AbstractFlowTest;
import com.awesome.payment.support.RecordingSpanExporter;
import com.awesome.payment.tracing.Spans;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.tracing.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real {@code RestBackendClient} bean, instrumented by Spring Boot, against a local HTTP stub:
 * the {@code traceparent} the backend receives is the hand-built child of the work's trace with
 * tracing off, and the client span's with tracing on (lab contract section 4).
 */
class BackendCallPropagationFlowTest extends AbstractFlowTest {

    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String PARENT = "00f067aa0ba902b7";
    private static final String ORDER = "{\"id\":42,\"username\":\"client\",\"status\":\"PENDING\",\"totalAmount\":9.99}";
    private static final List<Map<String, String>> REQUESTS = new CopyOnWriteArrayList<>();
    /** Shared by both application contexts; stopped with the test JVM. */
    private static final HttpServer STUB = stub();

    @Autowired @Qualifier("backendClient") private BackendClient restBackendClient;
    @Autowired private Spans spans;

    @DynamicPropertySource
    static void backendUrl(DynamicPropertyRegistry registry) {
        registry.add("backend.base-url", () -> "http://localhost:" + STUB.getAddress().getPort());
    }

    @BeforeEach
    void clearRequests() {
        REQUESTS.clear();
    }

    @Test
    void withTracingOffTheBackendGetsAHandBuiltChildOfTheWorksTrace() {
        assertThat(spans.enabled()).isFalse();

        try (Spans.Scope ignored = spans.child("dispatch payment-result", Span.Kind.PRODUCER, "req-off-1", TRACE,
                PARENT)) {
            restBackendClient.getOrder(42);
        }

        assertThat(REQUESTS).singleElement().satisfies(headers -> {
            assertThat(headers.get("x-request-id")).isEqualTo("req-off-1");
            assertThat(headers.get("traceparent-count")).isEqualTo("1");
            Correlation.TraceParent sent = Correlation.parseTraceparent(headers.get("traceparent"));
            assertThat(sent.traceId()).isEqualTo(TRACE);
            assertThat(sent.parentId()).isNotEqualTo(PARENT);
            assertThat(sent.flags()).isEqualTo("01");
        });
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void withTracingOffACallOutsideAnyWorkStartsAFreshRequestIdAndTrace() {
        restBackendClient.getOrder(42);

        assertThat(REQUESTS).singleElement().satisfies(headers -> {
            assertThat(headers.get("x-request-id")).matches("^[0-9a-f-]{36}$");
            assertThat(Correlation.parseTraceparent(headers.get("traceparent"))).isNotNull();
        });
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Nested
    @TestPropertySource(properties = {"tracing.enabled=true", "management.tracing.export.otlp.enabled=false"})
    class TracingOn {

        @TestConfiguration(proxyBeanMethods = false)
        static class RecordingExporterConfig {

            @Bean
            RecordingSpanExporter recordingSpanExporter() {
                return new RecordingSpanExporter();
            }
        }

        @Autowired private RecordingSpanExporter exporter;
        @Autowired private SdkTracerProvider tracerProvider;

        @Test
        void theBackendGetsTheClientSpansTraceparent() {
            assertThat(spans.enabled()).isTrue();
            exporter.clear();

            try (Spans.Scope ignored = spans.child("dispatch payment-result", Span.Kind.PRODUCER, "req-on-1", TRACE,
                    PARENT)) {
                restBackendClient.getOrder(42);
            }

            tracerProvider.forceFlush().join(5, TimeUnit.SECONDS);
            SpanData producer = exporter.spans(SpanKind.PRODUCER).getFirst();
            SpanData client = exporter.spans(SpanKind.CLIENT).getFirst();
            assertThat(producer.getParentSpanId()).isEqualTo(PARENT);
            assertThat(client.getTraceId()).isEqualTo(TRACE);
            assertThat(client.getParentSpanId()).isEqualTo(producer.getSpanId());
            assertThat(REQUESTS).singleElement().satisfies(headers -> {
                assertThat(headers.get("x-request-id")).isEqualTo("req-on-1");
                assertThat(headers.get("traceparent-count")).isEqualTo("1");
                assertThat(headers.get("traceparent")).isEqualTo("00-" + TRACE + "-" + client.getSpanId() + "-01");
            });
            assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
        }

        @Test
        void aCallOutsideAnyWorkIsNotTraced() {
            exporter.clear();

            restBackendClient.getOrder(42);

            tracerProvider.forceFlush().join(5, TimeUnit.SECONDS);
            assertThat(exporter.spans()).isEmpty();
            assertThat(REQUESTS).singleElement().satisfies(headers ->
                    assertThat(headers.get("traceparent")).matches("^00-[0-9a-f]{32}-[0-9a-f]{16}-0[02]$"));
        }
    }

    private static HttpServer stub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/internal/v1/orders/42", exchange -> {
                REQUESTS.add(Map.of(
                        "x-request-id", String.valueOf(exchange.getRequestHeaders().getFirst("X-Request-Id")),
                        "traceparent", String.valueOf(exchange.getRequestHeaders().getFirst("traceparent")),
                        "traceparent-count", String.valueOf(exchange.getRequestHeaders().get("traceparent").size())));
                byte[] body = ORDER.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
