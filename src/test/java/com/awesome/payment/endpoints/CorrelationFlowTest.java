package com.awesome.payment.endpoints;

import com.awesome.payment.correlation.Correlation;
import com.awesome.payment.dto.payment.PaymentDto;
import com.awesome.payment.entity.PaymentEntity;
import com.awesome.payment.entity.PaymentOutboxEntity;
import com.awesome.payment.entity.PaymentStatus;
import com.awesome.payment.events.CardRefundRequestedHandler;
import com.awesome.payment.repository.RefundRepository;
import com.awesome.payment.service.notification.PaymentResultDispatcher;
import com.awesome.payment.support.AbstractFlowTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Correlation ids end to end: the response header, the ids stored with a payment and its outbox
 * rows, the notification sent under them (also by the settlement job and the retry), card refunds
 * from feed events and the {@code [traceId,requestId]} log pattern.
 */
@ExtendWith(OutputCaptureExtension.class)
class CorrelationFlowTest extends AbstractFlowTest {

    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String PARENT = "00f067aa0ba902b7";
    private static final String TRACEPARENT = "00-" + TRACE + "-" + PARENT + "-01";

    @Autowired private PaymentResultDispatcher dispatcher;
    @Autowired private RefundRepository refundRepository;

    @AfterEach
    void noCorrelationLeaksOutOfAnything() {
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void aValidRequestIdIsAnsweredAndAnInvalidOneIsReplaced() {
        Response kept = perform(get("/actuator/health").header("X-Request-Id", "gw-1.a_B"), null);
        Response replaced = perform(get("/actuator/health").header("X-Request-Id", "not valid!"), null);
        Response generated = perform(get("/actuator/health"), null);

        assertThat(kept.header("X-Request-Id")).isEqualTo("gw-1.a_B");
        assertThat(replaced.header("X-Request-Id")).isNotEqualTo("not valid!").matches("^[0-9a-f-]{36}$");
        assertThat(generated.header("X-Request-Id")).matches("^[0-9a-f-]{36}$");
        assertThat(replaced.header("X-Request-Id")).isNotEqualTo(generated.header("X-Request-Id"));
    }

    @Test
    void rejectedRequestsCarryTheRequestIdToo() {
        Response unauthorized = perform(get("/api/v1/admin/payments").header("X-Request-Id", "unauth-1"), null);
        Response forbidden = perform(get("/api/v1/admin/payments").header("X-Request-Id", "forbidden-1"), clientToken);

        assertThat(unauthorized.status()).isEqualTo(401);
        assertThat(unauthorized.header("X-Request-Id")).isEqualTo("unauth-1");
        assertThat(forbidden.status()).isEqualTo(403);
        assertThat(forbidden.header("X-Request-Id")).isEqualTo("forbidden-1");
    }

    @Test
    void aPaymentKeepsTheIdsOfItsRequestAndTheNotificationCarriesThem(CapturedOutput output) {
        long orderId = backend.order("client", "126.00").id();

        Response response = payWithIds(orderId, VISA, "req-visa-1", TRACEPARENT);

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.header("X-Request-Id")).isEqualTo("req-visa-1");
        PaymentEntity payment = paymentRepository.findById(response.as(PaymentDto.class).id()).orElseThrow();
        assertThat(payment.getCorrelationRequestId()).isEqualTo("req-visa-1");
        Correlation.TraceParent stored = Correlation.parseTraceparent(payment.getTraceparent());
        assertThat(stored.traceId()).isEqualTo(TRACE);
        assertThat(stored.parentId()).as("this service's own span, not the caller's").isNotEqualTo(PARENT);
        assertThat(outboxRepository.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getCorrelationRequestId()).isEqualTo("req-visa-1");
            assertThat(row.getTraceparent()).isEqualTo(payment.getTraceparent());
        });
        assertThat(backend.deliveries()).singleElement().satisfies(delivery -> {
            assertThat(delivery.requestId()).isEqualTo("req-visa-1");
            assertThat(delivery.traceId()).isEqualTo(TRACE);
        });
        assertThat(output.getOut()).containsPattern(
                "\\[" + TRACE + ",req-visa-1] .*Payment " + payment.getId() + " for order " + orderId);
        assertThat(output.getOut()).containsPattern(
                "\\[" + TRACE + ",req-visa-1] .*Delivered payment result");
    }

    @Test
    void withoutIncomingIdsThePaymentGetsGeneratedOnes() {
        long orderId = backend.order("client", "126.00").id();

        Response response = payWithIds(orderId, VISA, null, null);

        PaymentEntity payment = paymentRepository.findById(response.as(PaymentDto.class).id()).orElseThrow();
        assertThat(payment.getCorrelationRequestId()).isEqualTo(response.header("X-Request-Id"));
        assertThat(Correlation.parseTraceparent(payment.getTraceparent())).isNotNull();
        assertThat(backend.deliveries()).singleElement().satisfies(delivery -> {
            assertThat(delivery.requestId()).isEqualTo(payment.getCorrelationRequestId());
            assertThat(delivery.traceId()).isEqualTo(Correlation.traceIdOf(payment.getTraceparent()));
        });
    }

    @Test
    void theSettlementJobSendsTheIdsOfTheRequestThatCreatedTheAsyncPayment(CapturedOutput output) {
        long orderId = backend.order("client", "126.00").id();
        PaymentDto processing = payWithIds(orderId, SETTLES, "req-async-1", TRACEPARENT).as(PaymentDto.class);
        assertThat(backend.deliveries()).isEmpty();

        clock.advance(Duration.ofSeconds(10));
        assertThat(settle()).isEqualTo(1);

        assertThat(paymentRepository.findById(processing.id()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.CAPTURED);
        assertThat(outboxRepository.findAll()).singleElement()
                .extracting(PaymentOutboxEntity::getCorrelationRequestId).isEqualTo("req-async-1");
        assertThat(backend.deliveries()).singleElement().satisfies(delivery -> {
            assertThat(delivery.requestId()).isEqualTo("req-async-1");
            assertThat(delivery.traceId()).isEqualTo(TRACE);
        });
        assertThat(output.getOut()).containsPattern(
                "\\[" + TRACE + ",req-async-1] .*Payment " + processing.id() + " for order " + orderId + " settled");
    }

    @Test
    void aRetriedNotificationIsSentUnderTheStoredIds() {
        backend.failNextResults(503);
        long orderId = backend.order("client", "126.00").id();
        payWithIds(orderId, VISA, "req-retry-1", TRACEPARENT);
        assertThat(backend.deliveries()).isEmpty();

        clock.advance(Duration.ofSeconds(31));
        assertThat(dispatcher.dispatchDue()).isEqualTo(1);

        assertThat(backend.deliveries()).singleElement().satisfies(delivery -> {
            assertThat(delivery.requestId()).isEqualTo("req-retry-1");
            assertThat(delivery.traceId()).isEqualTo(TRACE);
        });
    }

    @Test
    void aRowWithoutStoredIdsIsSentUnderTheCurrentContext() {
        backend.failNextResults(503);
        PaymentDto payment = paid(VISA);
        outboxRepository.findAll().forEach(row -> {
            row.setCorrelationRequestId(null);
            row.setTraceparent(null);
            outboxRepository.save(row);
        });
        clock.advance(Duration.ofSeconds(31));

        try (Correlation.Scope ignored = Correlation.open("legacy-retry", TRACE)) {
            dispatcher.dispatchDue();
        }

        assertThat(backend.deliveries()).singleElement().satisfies(delivery -> {
            assertThat(delivery.orderId()).isEqualTo(payment.orderId());
            assertThat(delivery.requestId()).isEqualTo("legacy-retry");
            assertThat(delivery.traceId()).isEqualTo(TRACE);
        });
    }

    @Test
    void theThreeDsResultIsSentWithTheIdsOfTheRequestThatCreatedThePayment() {
        long orderId = backend.order("client", "126.00").id();
        PaymentDto challenged = payWithIds(orderId, THREE_DS, "req-3ds-create", TRACEPARENT).as(PaymentDto.class);

        Response answered = perform(post(PAYMENTS + "/" + challenged.id() + "/3ds")
                .header("X-Request-Id", "req-3ds-answer")
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"123456\"}"), clientToken);

        assertThat(answered.header("X-Request-Id")).isEqualTo("req-3ds-answer");
        assertThat(backend.deliveries()).singleElement().satisfies(delivery -> {
            assertThat(delivery.body().get("status").asString()).isEqualTo("CAPTURED");
            assertThat(delivery.requestId()).isEqualTo("req-3ds-create");
            assertThat(delivery.traceId()).isEqualTo(TRACE);
        });
    }

    @Test
    void aCardRefundLogsAndKeepsTheIdsOfTheBackendRequestBehindTheEvent(CapturedOutput output) {
        PaymentDto payment = paid(VISA);
        String cancelTrace = "0af7651916cd43dd8448eb211c80319c";
        UUID refundRequest = UUID.randomUUID();
        backend.publish(UUID.randomUUID(), CardRefundRequestedHandler.EVENT_TYPE, payment.orderId(),
                Map.of("requestId", refundRequest.toString(), "orderId", payment.orderId(), "amount", "26.00"),
                "cancel-req-7", cancelTrace);

        assertThat(poller.poll()).isEqualTo(1);

        assertThat(refundRepository.findByRequestId(refundRequest)).hasValueSatisfying(refund -> {
            assertThat(refund.getCorrelationRequestId()).isEqualTo("cancel-req-7");
            assertThat(refund.getTraceId()).isEqualTo(cancelTrace);
        });
        assertThat(output.getOut()).containsPattern(
                "\\[" + cancelTrace + ",cancel-req-7] .*Refunded 26.00 of payment " + payment.id());
    }

    @Test
    void invalidOrMissingEventIdsAreNotUsed(CapturedOutput output) {
        PaymentDto payment = paid(VISA);
        UUID invalid = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        backend.publish(UUID.randomUUID(), CardRefundRequestedHandler.EVENT_TYPE, payment.orderId(),
                Map.of("requestId", invalid.toString(), "amount", "1.00"), "bad id\nforged", "0".repeat(32));
        backend.publish(CardRefundRequestedHandler.EVENT_TYPE, payment.orderId(),
                Map.of("requestId", missing.toString(), "amount", "2.00"));

        assertThat(poller.poll()).isEqualTo(2);

        for (UUID requestId : new UUID[] {invalid, missing}) {
            assertThat(refundRepository.findByRequestId(requestId)).hasValueSatisfying(refund -> {
                assertThat(refund.getCorrelationRequestId()).isNull();
                assertThat(refund.getTraceId()).isNull();
            });
        }
        assertThat(output.getOut()).doesNotContain("forged");
        assertThat(output.getOut()).contains("[,] ");
    }

    @Test
    void aSkippedEventIsLoggedWithItsIds(CapturedOutput output) {
        String trace = "0af7651916cd43dd8448eb211c80319c";
        backend.publish(UUID.randomUUID(), CardRefundRequestedHandler.EVENT_TYPE, 1L, Map.of("amount", "x"),
                "bad-event-req", trace);

        poller.poll();

        assertThat(processedEvents.count()).isEqualTo(1);
        assertThat(output.getOut()).containsPattern("\\[" + trace + ",bad-event-req] .*failed and is skipped");
    }

    private Response payWithIds(long orderId, String number, String requestId, String traceparent) {
        MockHttpServletRequestBuilder builder = post(PAYMENTS)
                .header("Idempotency-Key", newKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(payment(orderId, number)));
        if (requestId != null) {
            builder.header("X-Request-Id", requestId);
        }
        if (traceparent != null) {
            builder.header("traceparent", traceparent);
        }
        return perform(builder, clientToken);
    }
}
