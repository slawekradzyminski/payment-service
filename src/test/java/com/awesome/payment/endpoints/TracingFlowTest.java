package com.awesome.payment.endpoints;

import com.awesome.payment.correlation.Correlation;
import com.awesome.payment.dto.payment.PaymentDto;
import com.awesome.payment.entity.PaymentEntity;
import com.awesome.payment.events.CardRefundRequestedHandler;
import com.awesome.payment.repository.RefundRepository;
import com.awesome.payment.support.AbstractFlowTest;
import com.awesome.payment.support.RecordingSpanExporter;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Tracing on ({@code TRACING_ENABLED=true}), lab contract section 4: the server span continues the
 * caller's trace, the result dispatch is a child of the stored {@code traceparent}, feed events are
 * consumed in {@code process <type>} spans parented by the event's ids, settlements are root spans,
 * and the MDC ids in the logs are the spans' ids. Spans go to a {@link RecordingSpanExporter}
 * instead of OTLP.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {
        "tracing.enabled=true",
        "management.tracing.export.otlp.enabled=false"
})
class TracingFlowTest extends AbstractFlowTest {

    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String PARENT = "00f067aa0ba902b7";
    private static final String TRACEPARENT = "00-" + TRACE + "-" + PARENT + "-01";
    private static final String NO_PARENT = "0000000000000000";

    @TestConfiguration(proxyBeanMethods = false)
    static class RecordingExporterConfig {

        @Bean
        RecordingSpanExporter recordingSpanExporter() {
            return new RecordingSpanExporter();
        }
    }

    @Autowired private RecordingSpanExporter exporter;
    @Autowired private SdkTracerProvider tracerProvider;
    @Autowired private ObservationRegistry observationRegistry;
    @Autowired private RefundRepository refundRepository;

    @BeforeEach
    void clearSpans() {
        flush();
        exporter.clear();
    }

    @AfterEach
    void noCorrelationLeaksOutOfAnything() {
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void aPaymentIsAServerSpanOfTheCallersTraceAndItsResultIsDispatchedAsItsChild(CapturedOutput output) {
        long orderId = backend.order("client", "126.00").id();

        Response response = payWithIds(orderId, "4242 4242 4242 4242", "req-trace-1", TRACEPARENT);

        assertThat(response.status()).isEqualTo(201);
        SpanData server = only(SpanKind.SERVER);
        assertThat(server.getTraceId()).isEqualTo(TRACE);
        assertThat(server.getParentSpanId()).isEqualTo(PARENT);
        assertThat(server.getName()).isEqualTo("http post /api/v1/payments");
        PaymentEntity payment = paymentRepository.findById(response.as(PaymentDto.class).id()).orElseThrow();
        assertThat(payment.getTraceparent()).isEqualTo("00-" + TRACE + "-" + server.getSpanId() + "-01");
        assertThat(payment.getCorrelationRequestId()).isEqualTo("req-trace-1");
        SpanData dispatch = only(SpanKind.PRODUCER);
        assertThat(dispatch.getName()).isEqualTo("dispatch payment-result");
        assertThat(dispatch.getTraceId()).isEqualTo(TRACE);
        assertThat(dispatch.getParentSpanId()).isEqualTo(server.getSpanId());
        assertThat(dispatch.getAttributes().asMap().toString()).contains("order.id=" + orderId);
        assertThat(backend.deliveries()).singleElement().satisfies(delivery -> {
            assertThat(delivery.requestId()).isEqualTo("req-trace-1");
            assertThat(delivery.traceId()).isEqualTo(TRACE);
        });
        assertThat(output.getOut()).containsPattern("\\[" + TRACE + ",req-trace-1] .*Payment " + payment.getId());
    }

    @Test
    void aRequestWithoutTraceparentStartsANewSampledTrace() {
        long orderId = backend.order("client", "126.00").id();

        Response response = payWithIds(orderId, "4242 4242 4242 4242", null, null);

        SpanData server = only(SpanKind.SERVER);
        assertThat(server.getParentSpanContext().isValid()).isFalse();
        PaymentEntity payment = paymentRepository.findById(response.as(PaymentDto.class).id()).orElseThrow();
        assertThat(payment.getTraceparent()).isEqualTo("00-" + server.getTraceId() + "-" + server.getSpanId() + "-01");
        assertThat(only(SpanKind.PRODUCER).getParentSpanId()).isEqualTo(server.getSpanId());
    }

    @Test
    void aFeedEventIsConsumedInAChildSpanOfTheBackendSpanThatWroteIt(CapturedOutput output) {
        PaymentDto payment = paid("4242 4242 4242 4242");
        flush();
        exporter.clear();
        String cancelTrace = "0af7651916cd43dd8448eb211c80319c";
        String cancelSpan = "b7ad6b7169203331";
        UUID refundRequest = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        backend.publish(eventId, CardRefundRequestedHandler.EVENT_TYPE, payment.orderId(),
                Map.of("requestId", refundRequest.toString(), "orderId", payment.orderId(), "amount", "26.00"),
                "cancel-req-9", cancelTrace, cancelSpan);

        assertThat(poller.poll()).isEqualTo(1);

        SpanData consumer = only(SpanKind.CONSUMER);
        assertThat(consumer.getName()).isEqualTo("process card-refund.requested");
        assertThat(consumer.getTraceId()).isEqualTo(cancelTrace);
        assertThat(consumer.getParentSpanId()).isEqualTo(cancelSpan);
        assertThat(consumer.getAttributes().asMap().toString()).contains("event.id=" + eventId);
        assertThat(refundRepository.findByRequestId(refundRequest)).hasValueSatisfying(refund -> {
            assertThat(refund.getTraceId()).isEqualTo(cancelTrace);
            assertThat(refund.getCorrelationRequestId()).isEqualTo("cancel-req-9");
        });
        assertThat(output.getOut()).containsPattern(
                "\\[" + cancelTrace + ",cancel-req-9] .*Refunded 26.00 of payment " + payment.id());
    }

    @Test
    void aFeedEventWithoutSpanIdStaysInItsTrace() {
        String trace = "0af7651916cd43dd8448eb211c80319c";
        backend.publish(UUID.randomUUID(), CardRefundRequestedHandler.EVENT_TYPE, 1L, Map.of("amount", "x"),
                "old-backend-req", trace);

        poller.poll();

        SpanData consumer = only(SpanKind.CONSUMER);
        assertThat(consumer.getTraceId()).isEqualTo(trace);
        assertThat(consumer.getParentSpanContext().isValid()).isTrue();
        assertThat(consumer.getStatus().getStatusCode()).as("the event failed and was skipped")
                .isEqualTo(io.opentelemetry.api.trace.StatusCode.ERROR);
    }

    @Test
    void aFeedEventWithoutIdsStartsANewTrace() {
        backend.publish(CardRefundRequestedHandler.EVENT_TYPE, 1L, Map.of("amount", "x"));

        poller.poll();

        assertThat(only(SpanKind.CONSUMER).getParentSpanId()).isEqualTo(NO_PARENT);
        assertThat(exporter.spans(SpanKind.CLIENT)).as("feed polls are not traced").isEmpty();
    }

    @Test
    void aSettlementIsARootSpanLinkedToTheRequestThatCreatedThePayment(CapturedOutput output) {
        long orderId = backend.order("client", "126.00").id();
        PaymentDto processing = payWithIds(orderId, "4000 0000 0000 0077", "req-async-9", TRACEPARENT)
                .as(PaymentDto.class);
        SpanData server = only(SpanKind.SERVER);
        exporter.clear();

        clock.advance(Duration.ofSeconds(10));
        assertThat(settle()).isEqualTo(1);
        flush();

        List<SpanData> settlements = exporter.named("settle payment");
        assertThat(settlements).singleElement().satisfies(settlement -> {
            assertThat(settlement.getKind()).isEqualTo(SpanKind.INTERNAL);
            assertThat(settlement.getParentSpanId()).isEqualTo(NO_PARENT);
            assertThat(settlement.getTraceId()).isNotEqualTo(TRACE);
            assertThat(settlement.getLinks()).singleElement().satisfies(link -> {
                assertThat(link.getSpanContext().getTraceId()).isEqualTo(TRACE);
                assertThat(link.getSpanContext().getSpanId()).isEqualTo(server.getSpanId());
            });
            assertThat(output.getOut()).containsPattern("\\[" + settlement.getTraceId()
                    + ",req-async-9] .*Payment " + processing.id() + " for order " + orderId + " settled");
        });
        SpanData dispatch = only(SpanKind.PRODUCER);
        assertThat(dispatch.getTraceId()).as("the outbox row keeps the payment's ids").isEqualTo(TRACE);
        assertThat(dispatch.getParentSpanId()).isEqualTo(server.getSpanId());
    }

    @Test
    void aRetriedDispatchIsAChildOfTheStoredTraceparentAndMarkedFailedWhenItFails() {
        backend.failNextResults(503);
        long orderId = backend.order("client", "126.00").id();
        payWithIds(orderId, "4242 4242 4242 4242", "req-retry-9", TRACEPARENT);
        SpanData server = only(SpanKind.SERVER);
        assertThat(only(SpanKind.PRODUCER).getStatus().getStatusCode())
                .isEqualTo(io.opentelemetry.api.trace.StatusCode.ERROR);
        exporter.clear();

        clock.advance(Duration.ofSeconds(31));
        assertThat(resultDispatcher().dispatchDue()).isEqualTo(1);

        SpanData retry = only(SpanKind.PRODUCER);
        assertThat(retry.getTraceId()).isEqualTo(TRACE);
        assertThat(retry.getParentSpanId()).isEqualTo(server.getSpanId());
        assertThat(retry.getAttributes().asMap().toString()).contains("payment.result.attempt=2");
    }

    @Test
    void schedulerTicksAndSecurityInternalsAreNotObserved() {
        assertThat(Observation.createNotStarted("tasks.scheduled.execution", observationRegistry).isNoop()).isTrue();
        assertThat(Observation.createNotStarted("spring.security.filterchains", observationRegistry).isNoop()).isTrue();
        assertThat(Observation.createNotStarted("spring.security.authorizations", observationRegistry).isNoop()).isTrue();
        assertThat(Observation.createNotStarted("http.client.requests", observationRegistry).isNoop()).isFalse();
        assertThat(Observation.createNotStarted("http.server.requests", observationRegistry).isNoop()).isFalse();
    }

    @Test
    void aPaymentRequestHasOnlyItsServerSpanAndTheDispatch() {
        payWithIds(backend.order("client", "126.00").id(), "4242 4242 4242 4242", null, TRACEPARENT);

        flush();
        assertThat(exporter.spans()).extracting(SpanData::getName)
                .containsExactlyInAnyOrder("http post /api/v1/payments", "dispatch payment-result");
    }

    private com.awesome.payment.service.notification.PaymentResultDispatcher resultDispatcher() {
        return dispatcher;
    }

    @Autowired private com.awesome.payment.service.notification.PaymentResultDispatcher dispatcher;

    private SpanData only(SpanKind kind) {
        flush();
        List<SpanData> spans = exporter.spans(kind);
        assertThat(spans).as(kind + " spans of " + exporter.spans()).hasSize(1);
        return spans.getFirst();
    }

    private void flush() {
        tracerProvider.forceFlush().join(5, TimeUnit.SECONDS);
    }

    private Response payWithIds(long orderId, String number, String requestId, String traceparent) {
        MockHttpServletRequestBuilder builder = post(PAYMENTS)
                .header("Idempotency-Key", newKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(payment(orderId, number)));
        if (requestId != null) {
            builder.header(Correlation.REQUEST_ID_HEADER, requestId);
        }
        if (traceparent != null) {
            builder.header(Correlation.TRACEPARENT_HEADER, traceparent);
        }
        return perform(builder, clientToken);
    }
}
