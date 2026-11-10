package com.awesome.payment.endpoints;

import com.awesome.payment.client.OrderStatus;
import com.awesome.payment.dto.payment.PaymentDto;
import com.awesome.payment.entity.PaymentOutboxEntity;
import com.awesome.payment.entity.PaymentStatus;
import com.awesome.payment.service.notification.PaymentResultDispatcher;
import com.awesome.payment.support.AbstractFlowTest;
import com.awesome.payment.support.FakeBackend;
import com.awesome.payment.support.MutableClock;
import com.awesome.payment.support.TestBackendConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResultOutboxFlowTest extends AbstractFlowTest {

    @Autowired
    private PaymentResultDispatcher dispatcher;

    @Test
    void theSignatureIsAnHmacOfTheTimestampAndTheExactBody() throws Exception {
        PaymentDto payment = paid(VISA);

        FakeBackend.Delivery delivery = backend.deliveries().getFirst();
        long t = MutableClock.START.getEpochSecond();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(TestBackendConfig.WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = HexFormat.of().formatHex(mac.doFinal((t + "." + delivery.rawBody())
                .getBytes(StandardCharsets.UTF_8)));
        assertThat(delivery.signature()).isEqualTo("t=" + t + ",v1=" + expected);
        assertThat(delivery.rawBody()).isEqualTo("{\"requestId\":\"" + delivery.body().get("requestId").asString()
                + "\",\"paymentId\":\"" + payment.id() + "\",\"status\":\"CAPTURED\",\"amount\":126.00,"
                + "\"currency\":\"USD\",\"failureCode\":null}");
    }

    @Test
    void duplicateNotificationCardDeliversTheSameRequestIdTwice() {
        PaymentDto payment = paid(DUPLICATE_NOTIFICATION);

        assertThat(payment.status()).isEqualTo(PaymentStatus.CAPTURED);
        List<FakeBackend.Delivery> deliveries = backend.deliveries();
        assertThat(deliveries).hasSize(2);
        assertThat(deliveries.get(0).rawBody()).isEqualTo(deliveries.get(1).rawBody());
        assertThat(deliveries).allSatisfy(d -> assertThat(d.signatureValid()).isTrue());
        assertThat(outboxRepository.findByPaymentIdOrderById(payment.id()))
                .hasSize(2).allSatisfy(row -> assertThat(row.getSentAt()).isNotNull());
        assertThat(backend.order(payment.orderId()).status()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void serverErrorsAreRetriedWithDoublingBackoff() {
        backend.failNextResults(503, 0, 500);
        PaymentDto payment = paid(VISA);
        PaymentOutboxEntity row = row(payment);
        assertThat(row.getSentAt()).isNull();
        assertThat(row.getAttempts()).isEqualTo(1);
        assertThat(row.getLastError()).isEqualTo("503 Backend unavailable");
        assertThat(row.getNextAttemptAt()).isEqualTo(now().plusSeconds(30));

        clock.advance(Duration.ofSeconds(29));
        assertThat(dispatcher.dispatchDue()).isZero();
        assertThat(backend.deliveries()).isEmpty();

        clock.advance(Duration.ofSeconds(1));
        assertThat(dispatcher.dispatchDue()).isZero();
        row = row(payment);
        assertThat(row.getAttempts()).isEqualTo(2);
        assertThat(row.getLastError()).isEqualTo("0 Backend unavailable");
        assertThat(row.getNextAttemptAt()).isEqualTo(now().plusSeconds(60));

        clock.advance(Duration.ofSeconds(60));
        dispatcher.dispatchDue();
        assertThat(row(payment).getNextAttemptAt()).isEqualTo(now().plusSeconds(120));

        clock.advance(Duration.ofSeconds(120));
        assertThat(dispatcher.dispatchDue()).isEqualTo(1);
        row = row(payment);
        assertThat(row.getSentAt()).isEqualTo(now());
        assertThat(row.getAttempts()).isEqualTo(3);
        assertThat(backend.order(payment.orderId()).status()).isEqualTo(OrderStatus.PAID);
        assertThat(backend.deliveries()).singleElement().satisfies(d -> {
            assertThat(d.signatureValid()).isTrue();
            assertThat(d.timestamp()).isEqualTo(clock.instant().getEpochSecond());
        });
    }

    @Test
    void retriesStopAfterTenAttempts() {
        backend.failNextResults(502, 502, 502, 502, 502, 502, 502, 502, 502, 502, 502);
        PaymentDto payment = paid(VISA);
        for (int i = 0; i < 12; i++) {
            clock.advance(Duration.ofMinutes(30));
            dispatcher.dispatchDue();
        }

        PaymentOutboxEntity row = row(payment);
        assertThat(row.getAttempts()).isEqualTo(10);
        assertThat(row.getSentAt()).isNull();
        assertThat(backend.deliveries()).isEmpty();
    }

    @Test
    void theScheduledRetryIsSwitchedOffInTests() {
        backend.failNextResults(503);
        PaymentDto payment = paid(VISA);
        clock.advance(Duration.ofMinutes(5));

        dispatcher.retryDue();

        assertThat(row(payment).getSentAt()).isNull();
    }

    @Test
    void orderNotPayableVoidsTheCapture() {
        long orderId = backend.order("client", "40.00").id();
        PaymentDto pending = pay(orderId, THREE_DS, clientToken).as(PaymentDto.class);
        backend.setStatus(orderId, OrderStatus.CANCELLED);

        PaymentDto answered = threeDs(pending.id(), "123456", clientToken).as(PaymentDto.class);
        assertThat(answered.status()).isEqualTo(PaymentStatus.CAPTURED);

        PaymentDto voided = getJson(PAYMENTS + "/" + pending.id(), clientToken).as(PaymentDto.class);
        assertThat(voided.status()).isEqualTo(PaymentStatus.VOIDED);
        assertThat(voided.refundedAmount()).isEqualByComparingTo("40.00");
        assertThat(voided.failureCode()).isEqualTo("ORDER_NOT_PAYABLE");
        PaymentOutboxEntity row = row(pending);
        assertThat(row.getSentAt()).isNotNull();
        assertThat(row.getLastError()).isEqualTo("409 Order is not payable");
        assertThat(dispatcher.dispatchDue()).isZero();
        // A voided payment no longer blocks the order.
        backend.setStatus(orderId, OrderStatus.PENDING);
        assertThat(pay(orderId, VISA, clientToken).status()).isEqualTo(201);
    }

    @Test
    void orderPaidByAnotherPaymentVoidsTheSecondCapture() {
        long orderId = backend.order("client", "40.00").id();
        PaymentDto first = pay(orderId, THREE_DS, clientToken).as(PaymentDto.class);
        threeDs(first.id(), "000000", clientToken);
        PaymentDto second = pay(orderId, THREE_DS, clientToken).as(PaymentDto.class);
        backend.setStatus(orderId, OrderStatus.PAID);

        threeDs(second.id(), "123456", clientToken);

        assertThat(paymentRepository.findById(second.id()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.VOIDED);
    }

    @Test
    void otherRejectionsAreFinalAndLeaveThePaymentAlone() {
        backend.failNextResults(422);
        PaymentDto payment = paid(VISA);

        PaymentOutboxEntity row = row(payment);
        assertThat(row.getAttempts()).isEqualTo(10);
        assertThat(row.getSentAt()).isNull();
        assertThat(row.getLastError()).isEqualTo("422 Scripted rejection");
        assertThat(paymentRepository.findById(payment.id()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        clock.advance(Duration.ofHours(2));
        assertThat(dispatcher.dispatchDue()).isZero();
    }

    @Test
    void aRejectedSignatureIsNotRetried() {
        backend.failNextResults(401);
        PaymentDto payment = paid(VISA);

        assertThat(row(payment).getAttempts()).isEqualTo(10);
        assertThat(row(payment).getLastError()).isEqualTo("401 Backend unavailable");
    }

    @Test
    void aConflictOnAFailedResultDoesNotChangeThePayment() {
        backend.failNextResults(409);
        PaymentDto payment = paid(DECLINED);

        assertThat(paymentRepository.findById(payment.id()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(row(payment).getSentAt()).isNotNull();
    }

    private PaymentOutboxEntity row(PaymentDto payment) {
        return outboxRepository.findByPaymentIdOrderById(payment.id()).getFirst();
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
