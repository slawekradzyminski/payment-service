package com.awesome.payment.service;

import com.awesome.payment.correlation.Correlation;
import com.awesome.payment.entity.PaymentEntity;
import com.awesome.payment.entity.PaymentStatus;
import com.awesome.payment.entity.Settlement;
import com.awesome.payment.repository.PaymentRepository;
import com.awesome.payment.service.notification.PaymentResultOutbox;
import com.awesome.payment.service.provider.FakeCardProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The simulated provider's asynchronous side: PROCESSING payments whose
 * {@code payment.settlement-delay} has passed are captured, or fail with PROVIDER_TIMEOUT;
 * REQUIRES_ACTION payments older than {@code payment.three-ds-timeout} fail with
 * THREE_DS_EXPIRED. Each result is queued for the backend. Disabled with {@code payment.settlement.enabled}.
 * Each payment is settled (and its result delivered) under the correlation ids of the request that
 * created it, so its log lines and the notification share that request's trace.
 */
@Slf4j
@Component
public class SettlementJob {

    static final int BATCH_SIZE = 50;

    private final PaymentRepository paymentRepository;
    private final PaymentResultOutbox outbox;
    private final TransactionTemplate transaction;
    private final Clock paymentClock;
    private final boolean enabled;

    public SettlementJob(PaymentRepository paymentRepository, PaymentResultOutbox outbox,
                         PlatformTransactionManager transactionManager, Clock paymentClock,
                         @Value("${payment.settlement.enabled:true}") boolean enabled) {
        this.paymentRepository = paymentRepository;
        this.outbox = outbox;
        this.transaction = new TransactionTemplate(transactionManager);
        this.paymentClock = paymentClock;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${payment.settlement.interval:PT2S}",
            initialDelayString = "${payment.settlement.interval:PT2S}")
    public void scheduledSettle() {
        if (enabled) {
            settleDue();
        }
    }

    /** A due payment and the correlation ids of the request that created it. */
    private record Due(UUID id, String requestId, String traceId) {
    }

    /** Settles every due payment, each in its own transaction; returns how many were settled. */
    public synchronized int settleDue() {
        List<Due> due = transaction.execute(status -> paymentRepository
                .findDueForSettlement(LocalDateTime.now(paymentClock), PageRequest.of(0, BATCH_SIZE))
                .stream().map(p -> new Due(p.getId(), p.getCorrelationRequestId(),
                        Correlation.traceIdOf(p.getTraceparent()))).toList());
        int settled = 0;
        for (Due payment : due == null ? List.<Due>of() : due) {
            try (Correlation.Scope ignored = Correlation.open(payment.requestId(), payment.traceId())) {
                Boolean done = transaction.execute(status -> settle(payment.id()));
                if (Boolean.TRUE.equals(done)) {
                    settled++;
                }
            } catch (ObjectOptimisticLockingFailureException e) {
                log.info("Payment {} changed while settling; left for the next run", payment.id());
            }
        }
        return settled;
    }

    private boolean settle(UUID id) {
        PaymentEntity payment = paymentRepository.findById(id).orElse(null);
        LocalDateTime now = LocalDateTime.now(paymentClock);
        if (payment == null || payment.getSettleAt() == null || payment.getSettleAt().isAfter(now)) {
            return false;
        }
        if (payment.getStatus() == PaymentStatus.REQUIRES_ACTION) {
            PaymentStateMachine.fail(payment, FakeCardProvider.THREE_DS_EXPIRED,
                    FakeCardProvider.failureMessage(FakeCardProvider.THREE_DS_EXPIRED), now);
        } else if (payment.getSettlement() == Settlement.TIMEOUT) {
            PaymentStateMachine.fail(payment, FakeCardProvider.PROVIDER_TIMEOUT,
                    FakeCardProvider.failureMessage(FakeCardProvider.PROVIDER_TIMEOUT), now);
        } else {
            PaymentStateMachine.capture(payment, now);
        }
        paymentRepository.save(payment);
        outbox.enqueue(payment, 1);
        log.info("Payment {} for order {} settled: {}", payment.getId(), payment.getOrderId(), payment.getStatus());
        return true;
    }
}
