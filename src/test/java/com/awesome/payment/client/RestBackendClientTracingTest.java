package com.awesome.payment.client;

import com.awesome.payment.correlation.Correlation;
import com.awesome.payment.support.TracingKit;
import com.awesome.payment.tracing.Spans;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Propagation to the backend through the instrumented {@code RestClient} (lab contract section 4):
 * with tracing on, the {@code traceparent} is the client span's; the hand-built child
 * {@code traceparent} is only sent with tracing off.
 */
class RestBackendClientTracingTest {

    private static final String BASE = "http://backend:4001";
    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String ORDER_JSON = "{\"id\":42,\"username\":\"client\",\"status\":\"PENDING\",\"totalAmount\":1}";

    private final List<String> traceparents = new ArrayList<>();

    @AfterEach
    void noCorrelationLeaks() {
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void withTracingOnTheBackendCallIsAClientSpanOfTheCurrentSpanAndCarriesItsTraceparent() {
        TracingKit kit = new TracingKit(true);
        RestBackendClient client = client(kit, true);
        Spans spans = new Spans(kit.tracer, true);

        try (Spans.Scope ignored = spans.child("work", null, "req-7", TRACE_ID, "00f067aa0ba902b7")) {
            client.getOrder(42);
        }

        SpanData work = kit.exporter.named("work").getFirst();
        SpanData call = kit.exporter.spans(SpanKind.CLIENT).getFirst();
        assertThat(call.getTraceId()).isEqualTo(TRACE_ID);
        assertThat(call.getParentSpanId()).isEqualTo(work.getSpanId());
        assertThat(traceparents).containsExactly("00-" + TRACE_ID + "-" + call.getSpanId() + "-01");
    }

    @Test
    void withTracingOnACallOutsideAnyWorkIsNotTraced() {
        TracingKit kit = new TracingKit(true);
        RestBackendClient client = client(kit, true);

        client.getOrder(42);

        assertThat(kit.exporter.spans()).isEmpty();
        assertThat(traceparents).singleElement().satisfies(traceparent ->
                assertThat(traceparent).as("not sampled").matches("^00-[0-9a-f]{32}-[0-9a-f]{16}-0[02]$"));
    }

    @Test
    void withTracingOffTheHandBuiltChildTraceparentReplacesTheInstrumentationOne() {
        TracingKit kit = new TracingKit(false);
        RestBackendClient client = client(kit, false);

        try (Spans.Scope ignored = new Spans(kit.tracer, false).child("work", null, "req-8", TRACE_ID, null)) {
            client.getOrder(42);
        }

        assertThat(kit.exporter.spans()).isEmpty();
        assertThat(traceparents).singleElement().satisfies(traceparent -> {
            assertThat(traceparent).matches("^00-" + TRACE_ID + "-[0-9a-f]{16}-01$");
            assertThat(Correlation.parseTraceparent(traceparent).traceId()).isEqualTo(TRACE_ID);
        });
    }

    private RestBackendClient client(TracingKit kit, boolean tracingEnabled) {
        RestClient.Builder builder = RestClient.builder().observationRegistry(kit.observationRegistry);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE + "/internal/v1/orders/42"))
                .andExpect(header("X-Request-Id", org.hamcrest.Matchers.notNullValue()))
                .andExpect(request -> traceparents.addAll(request.getHeaders().get("traceparent")))
                .andRespond(withSuccess(ORDER_JSON, MediaType.APPLICATION_JSON));
        return new RestBackendClient(builder, BASE, "token", tracingEnabled);
    }
}
