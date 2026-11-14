package com.awesome.payment.service;

import com.awesome.payment.entity.PaymentEntity;
import com.awesome.payment.entity.PaymentStatus;
import com.awesome.payment.entity.Settlement;
import com.awesome.payment.repository.PaymentRepository;
import com.awesome.payment.service.notification.PaymentResultOutbox;
import com.awesome.payment.tracing.Spans;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The races the flow tests cannot produce: a payment that changed between listing and settling. */
class SettlementJobTest {

    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
    private static final LocalDateTime LOCAL_NOW = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);

    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final PaymentResultOutbox outbox = mock(PaymentResultOutbox.class);
    private PaymentEntity payment;

    @BeforeEach
    void setUp() {
        payment = PaymentEntity.builder().id(UUID.randomUUID()).orderId(1L).status(PaymentStatus.PROCESSING)
                .amount(BigDecimal.TEN).settlement(Settlement.CAPTURE).settleAt(LOCAL_NOW).build();
        when(payments.findDueForSettlement(any(), any())).thenReturn(List.of(payment));
        when(payments.findById(payment.getId())).thenReturn(Optional.of(payment));
    }

    @Test
    void aDuePaymentIsSettledAndQueued() {
        assertThat(job(true).settleDue()).isEqualTo(1);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        verify(outbox).enqueue(payment, 1);
    }

    @Test
    void aPaymentThatDisappearedIsSkipped() {
        when(payments.findById(payment.getId())).thenReturn(Optional.empty());

        assertThat(job(true).settleDue()).isZero();
        verify(outbox, never()).enqueue(any(), anyInt());
    }

    @Test
    void aPaymentNoLongerDueIsSkipped() {
        payment.setSettleAt(LOCAL_NOW.plusSeconds(1));

        assertThat(job(true).settleDue()).isZero();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PROCESSING);

        payment.setSettleAt(null);
        assertThat(job(true).settleDue()).isZero();
    }

    @Test
    void aConcurrentChangeLeavesThePaymentForTheNextRun() {
        when(payments.save(payment)).thenThrow(new ObjectOptimisticLockingFailureException(PaymentEntity.class,
                payment.getId()));

        assertThat(job(true).settleDue()).isZero();
        verify(outbox, never()).enqueue(any(), anyInt());
    }

    @Test
    void theScheduledRunHonoursTheSwitch() {
        job(false).scheduledSettle();
        verify(payments, never()).findDueForSettlement(any(), any());

        job(true).scheduledSettle();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
    }

    private SettlementJob job(boolean enabled) {
        return new SettlementJob(payments, outbox, new InlineTransactionManager(), Clock.fixed(NOW, ZoneOffset.UTC),
                Spans.disabled(), enabled);
    }

    static class InlineTransactionManager extends AbstractPlatformTransactionManager {

        private static final long serialVersionUID = 1L;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            // runs inline
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
