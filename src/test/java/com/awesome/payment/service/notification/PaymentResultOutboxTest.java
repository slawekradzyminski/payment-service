package com.awesome.payment.service.notification;

import com.awesome.payment.correlation.Correlation;
import com.awesome.payment.entity.PaymentEntity;
import com.awesome.payment.entity.PaymentOutboxEntity;
import com.awesome.payment.entity.PaymentStatus;
import com.awesome.payment.repository.PaymentOutboxRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class PaymentResultOutboxTest {

    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");

    private final PaymentOutboxRepository repository = mock(PaymentOutboxRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final PaymentResultOutbox outbox = new PaymentResultOutbox(repository, events,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void writesTheResultPayloadDueNowAndAnnouncesIt() {
        PaymentEntity payment = payment(PaymentStatus.FAILED, "CARD_DECLINED");

        UUID requestId = outbox.enqueue(payment, 1);

        ArgumentCaptor<PaymentOutboxEntity> saved = ArgumentCaptor.forClass(PaymentOutboxEntity.class);
        verify(repository).save(saved.capture());
        PaymentOutboxEntity row = saved.getValue();
        assertThat(row.getRequestId()).isEqualTo(requestId);
        assertThat(row.getPaymentId()).isEqualTo(payment.getId());
        assertThat(row.getOrderId()).isEqualTo(42L);
        assertThat(row.getAttempts()).isZero();
        assertThat(row.getNextAttemptAt()).isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        assertThat(row.getCreatedAt()).isEqualTo(row.getNextAttemptAt());
        assertThat(row.getSentAt()).isNull();
        assertThat(row.getPayload()).isEqualTo("{\"requestId\":\"" + requestId + "\",\"paymentId\":\"" + payment.getId()
                + "\",\"status\":\"FAILED\",\"amount\":9.50,\"currency\":\"USD\",\"failureCode\":\"CARD_DECLINED\"}");
        verify(events).publishEvent(any(PaymentResultOutbox.Enqueued.class));
    }

    @Test
    void theDuplicateDemoWritesTheSameRequestTwice() {
        outbox.enqueue(payment(PaymentStatus.CAPTURED, null), 2);

        ArgumentCaptor<PaymentOutboxEntity> saved = ArgumentCaptor.forClass(PaymentOutboxEntity.class);
        verify(repository, times(2)).save(saved.capture());
        List<PaymentOutboxEntity> rows = saved.getAllValues();
        assertThat(rows.get(0).getRequestId()).isEqualTo(rows.get(1).getRequestId());
        assertThat(rows.get(0).getPayload()).isEqualTo(rows.get(1).getPayload()).contains("\"failureCode\":null");
        verify(events, times(1)).publishEvent(any(PaymentResultOutbox.Enqueued.class));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(value = {
        "create-req, 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
        "create-req, NULL",
        "NULL, 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"}, nullValues = "NULL")
    void rowsKeepTheIdsOfTheRequestThatCreatedThePayment(String requestId, String traceparent) {
        PaymentEntity payment = payment(PaymentStatus.CAPTURED, null);
        payment.setCorrelationRequestId(requestId);
        payment.setTraceparent(traceparent);

        try (Correlation.Scope ignored = Correlation.open("settling-req", "0af7651916cd43dd8448eb211c80319c")) {
            outbox.enqueue(payment, 1);
        }

        ArgumentCaptor<PaymentOutboxEntity> saved = ArgumentCaptor.forClass(PaymentOutboxEntity.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getCorrelationRequestId()).isEqualTo(requestId);
        assertThat(saved.getValue().getTraceparent()).isEqualTo(traceparent);
    }

    @Test
    void aPaymentWithoutIdsTakesThoseOfTheCurrentRequest() {
        String currentTraceparent;
        try (Correlation.Scope ignored = Correlation.open("current-req", "0af7651916cd43dd8448eb211c80319c")) {
            currentTraceparent = Correlation.currentTraceparent();
            outbox.enqueue(payment(PaymentStatus.CAPTURED, null), 1);
        }

        ArgumentCaptor<PaymentOutboxEntity> saved = ArgumentCaptor.forClass(PaymentOutboxEntity.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getCorrelationRequestId()).isEqualTo("current-req");
        assertThat(saved.getValue().getTraceparent()).isEqualTo(currentTraceparent);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"CAPTURED", "FAILED"}, mode = EnumSource.Mode.EXCLUDE)
    void onlyFinalResultsAreSent(PaymentStatus status) {
        assertThatThrownBy(() -> outbox.enqueue(payment(status, null), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(status.name());
        verify(repository, never()).save(any());
        verify(events, never()).publishEvent(any(Object.class));
    }

    private static PaymentEntity payment(PaymentStatus status, String failureCode) {
        return PaymentEntity.builder().id(UUID.randomUUID()).orderId(42L).status(status)
                .amount(new BigDecimal("9.50")).currency("USD").failureCode(failureCode).build();
    }
}
