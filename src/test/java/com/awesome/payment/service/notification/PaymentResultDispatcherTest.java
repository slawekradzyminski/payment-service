package com.awesome.payment.service.notification;

import com.awesome.payment.client.BackendClient;
import com.awesome.payment.client.BackendErrorMapper;
import com.awesome.payment.client.BackendException;
import com.awesome.payment.entity.PaymentEntity;
import com.awesome.payment.entity.PaymentOutboxEntity;
import com.awesome.payment.entity.PaymentStatus;
import com.awesome.payment.repository.PaymentOutboxRepository;
import com.awesome.payment.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentResultDispatcherTest {

    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
    private static final LocalDateTime LOCAL_NOW = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
    private static final String PAYLOAD = "{\"status\":\"CAPTURED\"}";

    private final PaymentOutboxRepository outbox = mock(PaymentOutboxRepository.class);
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final BackendClient backend = mock(BackendClient.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final PaymentSignature signature = new PaymentSignature("secret", clock);
    private final RecordingTransactionManager transactions = new RecordingTransactionManager();
    private PaymentOutboxEntity row;
    private PaymentEntity payment;

    @BeforeEach
    void setUp() {
        payment = PaymentEntity.builder().id(UUID.randomUUID()).status(PaymentStatus.CAPTURED)
                .amount(new BigDecimal("12.00")).refundedAmount(new BigDecimal("0.00")).build();
        row = PaymentOutboxEntity.builder().id(7L).requestId(UUID.randomUUID()).paymentId(payment.getId()).orderId(42L)
                .payload(PAYLOAD).attempts(0).nextAttemptAt(LOCAL_NOW).createdAt(LOCAL_NOW).build();
        when(outbox.findDue(any(), anyInt(), any())).thenReturn(List.of(row));
        when(outbox.findById(7L)).thenReturn(Optional.of(row));
        when(payments.findById(payment.getId())).thenReturn(Optional.of(payment));
    }

    @Test
    void deliversTheStoredPayloadSignedNowAndMarksItSent() {
        assertThat(dispatcher(true).dispatchDue()).isEqualTo(1);

        verify(backend).sendPaymentResult(42L, PAYLOAD, signature.sign(PAYLOAD));
        verify(outbox).findDue(LOCAL_NOW, 10, PageRequest.of(0, 20));
        verify(outbox).save(row);
        assertThat(row.getSentAt()).isEqualTo(LOCAL_NOW);
        assertThat(row.getAttempts()).isZero();
        assertThat(transactions.propagations).isNotEmpty()
                .containsOnly(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Test
    void transientFailuresAreRetriedWithBackoff() {
        row.setAttempts(2);
        when(backend.sendPaymentResult(anyLong(), anyString(), anyString()))
                .thenThrow(BackendErrorMapper.fromResponse(HttpStatus.BAD_GATEWAY, null));

        assertThat(dispatcher(true).dispatchDue()).isZero();

        assertThat(row.getAttempts()).isEqualTo(3);
        assertThat(row.getNextAttemptAt()).isEqualTo(LOCAL_NOW.plusMinutes(2));
        assertThat(row.getLastError()).isEqualTo("502 Backend unavailable");
        assertThat(row.getSentAt()).isNull();
        verify(outbox).save(row);
        verify(payments, never()).save(any());
    }

    @Test
    void connectionFailuresAreRetried() {
        when(backend.sendPaymentResult(anyLong(), anyString(), anyString()))
                .thenThrow(BackendErrorMapper.unavailable(new IllegalStateException("reset")));

        dispatcher(true).dispatchDue();

        assertThat(row.getAttempts()).isEqualTo(1);
        assertThat(row.getNextAttemptAt()).isEqualTo(LOCAL_NOW.plusSeconds(30));
    }

    @Test
    void clientErrorsAreFinal() {
        when(backend.sendPaymentResult(anyLong(), anyString(), anyString()))
                .thenThrow(new BackendException("Amount mismatch", HttpStatus.UNPROCESSABLE_CONTENT, 422, null));

        dispatcher(true).dispatchDue();

        assertThat(row.getAttempts()).isEqualTo(10);
        assertThat(row.getLastError()).isEqualTo("422 Amount mismatch");
        assertThat(row.getSentAt()).isNull();
        assertThat(row.getNextAttemptAt()).isEqualTo(LOCAL_NOW.plusMinutes(30));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
    }

    @Test
    void notPayableVoidsTheCaptureAndClosesTheRow() {
        when(backend.sendPaymentResult(anyLong(), anyString(), anyString()))
                .thenThrow(new BackendException("Order is not payable", HttpStatus.CONFLICT, 409, null));

        assertThat(dispatcher(true).dispatchDue()).isZero();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.VOIDED);
        assertThat(payment.getRefundedAmount()).isEqualByComparingTo("12.00");
        verify(payments).save(payment);
        assertThat(row.getSentAt()).isEqualTo(LOCAL_NOW);
        assertThat(row.getLastError()).isEqualTo("409 Order is not payable");
        assertThat(row.getAttempts()).isZero();
        verify(outbox).save(row);
    }

    @Test
    void notPayableLeavesAFailedPaymentAlone() {
        payment.setStatus(PaymentStatus.FAILED);
        when(backend.sendPaymentResult(anyLong(), anyString(), anyString()))
                .thenThrow(new BackendException("Order is not payable", HttpStatus.CONFLICT, 409, null));

        dispatcher(true).dispatchDue();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        verify(payments, never()).save(any());
        assertThat(row.getSentAt()).isEqualTo(LOCAL_NOW);
    }

    @Test
    void theScheduledRetryHonoursTheSwitch() {
        dispatcher(false).retryDue();
        verify(outbox, never()).findDue(any(), anyInt(), any(Pageable.class));

        dispatcher(true).retryDue();
        verify(backend).sendPaymentResult(eq(42L), eq(PAYLOAD), anyString());
    }

    @Test
    void anEnqueueTriggersADispatchOnTheWorkerNotOnTheCommittingThread() {
        AtomicReference<String> sendingThread = new AtomicReference<>();
        when(backend.sendPaymentResult(anyLong(), anyString(), anyString())).thenAnswer(invocation -> {
            sendingThread.set(Thread.currentThread().getName());
            return null;
        });
        PaymentResultDispatcher dispatcher = dispatcher(false);

        dispatcher.afterEnqueue(new PaymentResultOutbox.Enqueued());

        assertThat(dispatcher.awaitTriggered(Duration.ofSeconds(5))).isTrue();
        verify(backend).sendPaymentResult(eq(42L), eq(PAYLOAD), anyString());
        assertThat(sendingThread.get()).isEqualTo("payment-result-dispatch")
                .isNotEqualTo(Thread.currentThread().getName());
    }

    @Test
    void triggersWhileARunIsQueuedAreCoalesced() throws Exception {
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(backend.sendPaymentResult(anyLong(), anyString(), anyString())).thenAnswer(invocation -> {
            sending.countDown();
            release.await();
            return null;
        });
        PaymentResultDispatcher dispatcher = dispatcher(false);

        dispatcher.afterEnqueue(new PaymentResultOutbox.Enqueued());
        assertThat(sending.await(5, TimeUnit.SECONDS)).isTrue();
        dispatcher.afterEnqueue(new PaymentResultOutbox.Enqueued());
        dispatcher.afterEnqueue(new PaymentResultOutbox.Enqueued());
        dispatcher.afterEnqueue(new PaymentResultOutbox.Enqueued());
        release.countDown();

        assertThat(dispatcher.awaitTriggered(Duration.ofSeconds(5))).isTrue();
        verify(outbox, times(2)).findDue(any(), anyInt(), any());
    }

    @Test
    void aFailedTriggeredRunIsLeftToTheScheduledRetry() {
        when(outbox.findDue(any(), anyInt(), any())).thenThrow(new IllegalStateException("database down"))
                .thenReturn(List.of(row));
        PaymentResultDispatcher dispatcher = dispatcher(false);

        dispatcher.afterEnqueue(new PaymentResultOutbox.Enqueued());
        assertThat(dispatcher.awaitTriggered(Duration.ofSeconds(5))).isTrue();
        verify(backend, never()).sendPaymentResult(anyLong(), anyString(), anyString());

        dispatcher.afterEnqueue(new PaymentResultOutbox.Enqueued());
        assertThat(dispatcher.awaitTriggered(Duration.ofSeconds(5))).isTrue();
        verify(backend).sendPaymentResult(eq(42L), eq(PAYLOAD), anyString());
    }

    @Test
    void afterShutdownTriggersAreLeftToTheScheduledRetry() throws Exception {
        PaymentResultDispatcher dispatcher = dispatcher(false);
        dispatcher.shutdown();

        dispatcher.afterEnqueue(new PaymentResultOutbox.Enqueued());

        assertThat(dispatcher.awaitTriggered(Duration.ofSeconds(1))).isFalse();
        verify(backend, never()).sendPaymentResult(anyLong(), anyString(), anyString());
        dispatcher.dispatchDue();
        verify(backend).sendPaymentResult(eq(42L), eq(PAYLOAD), anyString());
    }

    @Test
    void awaitingASlowRunTimesOut() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        when(backend.sendPaymentResult(anyLong(), anyString(), anyString())).thenAnswer(invocation -> {
            release.await();
            return null;
        });
        PaymentResultDispatcher dispatcher = dispatcher(false);

        dispatcher.afterEnqueue(new PaymentResultOutbox.Enqueued());

        assertThat(dispatcher.awaitTriggered(Duration.ofMillis(100))).isFalse();
        release.countDown();
        assertThat(dispatcher.awaitTriggered(Duration.ofSeconds(5))).isTrue();
    }

    @Test
    void nothingDueSendsNothing() {
        when(outbox.findDue(any(), anyInt(), any())).thenReturn(List.of());

        assertThat(dispatcher(true).dispatchDue()).isZero();
        verify(backend, never()).sendPaymentResult(anyLong(), anyString(), anyString());
    }

    @ParameterizedTest
    @CsvSource({"0, 30", "1, 30", "2, 60", "3, 120", "4, 240", "5, 480", "6, 960", "7, 1800", "10, 1800",
        "11, 1800", "40, 1800"})
    void backoffDoublesFromThirtySecondsUpToThirtyMinutes(int attempts, long seconds) {
        assertThat(PaymentResultDispatcher.backoff(attempts)).isEqualTo(Duration.ofSeconds(seconds));
    }

    @Test
    void errorsAreTruncatedTo500Characters() {
        assertThat(PaymentResultDispatcher.truncate("x".repeat(500))).hasSize(500);
        assertThat(PaymentResultDispatcher.truncate("x".repeat(501))).hasSize(500);
        assertThat(PaymentResultDispatcher.truncate("short")).isEqualTo("short");
    }

    private PaymentResultDispatcher dispatcher(boolean retryEnabled) {
        return new PaymentResultDispatcher(outbox, payments, backend, signature, transactions, clock, retryEnabled);
    }

    /** Runs callbacks inline and records the requested propagation. */
    static class RecordingTransactionManager extends AbstractPlatformTransactionManager {

        private static final long serialVersionUID = 1L;
        final List<Integer> propagations = new ArrayList<>();

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            propagations.add(definition.getPropagationBehavior());
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            // nothing to commit
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            // nothing to roll back
        }
    }
}
