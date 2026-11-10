package com.awesome.payment.service.notification;

import com.awesome.payment.client.BackendClient;
import com.awesome.payment.client.BackendException;
import com.awesome.payment.entity.PaymentOutboxEntity;
import com.awesome.payment.repository.PaymentOutboxRepository;
import com.awesome.payment.repository.PaymentRepository;
import com.awesome.payment.service.PaymentStateMachine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

/**
 * Delivers outbox payment results to the backend: right after the transaction that wrote them
 * commits, and again on a schedule for those that failed. No response or a 5xx is retried with
 * exponential backoff (30 s doubling to 30 min, at most {@value #MAX_ATTEMPTS} attempts). A 409
 * ("Order is not payable") to a capture voids the payment. Any other rejection is final. The
 * backend handles each requestId once, so a retry after a lost response is harmless.
 */
@Slf4j
@Component
public class PaymentResultDispatcher {

    static final int MAX_ATTEMPTS = 10;
    static final int BATCH_SIZE = 20;
    static final Duration FIRST_BACKOFF = Duration.ofSeconds(30);
    static final Duration MAX_BACKOFF = Duration.ofMinutes(30);
    private static final int ERROR_LENGTH = 500;

    private final PaymentOutboxRepository outboxRepository;
    private final PaymentRepository paymentRepository;
    private final BackendClient backendClient;
    private final PaymentSignature signature;
    private final TransactionTemplate transaction;
    private final Clock paymentClock;
    private final boolean retryEnabled;

    public PaymentResultDispatcher(PaymentOutboxRepository outboxRepository, PaymentRepository paymentRepository,
                                   BackendClient backendClient, PaymentSignature signature,
                                   PlatformTransactionManager transactionManager, Clock paymentClock,
                                   @Value("${payment.notifications.retry-enabled:true}") boolean retryEnabled) {
        this.outboxRepository = outboxRepository;
        this.paymentRepository = paymentRepository;
        this.backendClient = backendClient;
        this.signature = signature;
        this.transaction = new TransactionTemplate(transactionManager);
        // Runs after the enqueuing transaction committed, where REQUIRED would join that
        // finished transaction and silently lose every write.
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.paymentClock = paymentClock;
        this.retryEnabled = retryEnabled;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void afterEnqueue(PaymentResultOutbox.Enqueued event) {
        dispatchDue();
    }

    @Scheduled(fixedDelayString = "${payment.notifications.retry-interval:PT30S}",
            initialDelayString = "${payment.notifications.retry-interval:PT30S}")
    public void retryDue() {
        if (retryEnabled) {
            dispatchDue();
        }
    }

    /** Sends every due result; synchronized so one instance never sends a row twice at once. */
    public synchronized int dispatchDue() {
        List<PaymentOutboxEntity> due = transaction.execute(status -> outboxRepository.findDue(
                LocalDateTime.now(paymentClock), MAX_ATTEMPTS, PageRequest.of(0, BATCH_SIZE)));
        int sent = 0;
        for (PaymentOutboxEntity row : due == null ? List.<PaymentOutboxEntity>of() : due) {
            if (send(row)) {
                sent++;
            }
        }
        return sent;
    }

    private boolean send(PaymentOutboxEntity row) {
        try {
            backendClient.sendPaymentResult(row.getOrderId(), row.getPayload(), signature.sign(row.getPayload()));
            update(row.getId(), delivered -> delivered.setSentAt(LocalDateTime.now(paymentClock)));
            log.info("Delivered payment result {} for order {}", row.getRequestId(), row.getOrderId());
            return true;
        } catch (BackendException e) {
            handleFailure(row, e);
            return false;
        }
    }

    private void handleFailure(PaymentOutboxEntity row, BackendException e) {
        String error = truncate(e.getBackendStatus() + " " + e.getMessage());
        if (e.getBackendStatus() == HttpStatus.CONFLICT.value()) {
            log.warn("Backend refused payment result {} for order {}: {}; voiding the capture",
                    row.getRequestId(), row.getOrderId(), e.getMessage());
            transaction.executeWithoutResult(status -> {
                paymentRepository.findById(row.getPaymentId()).ifPresent(payment -> {
                    if (PaymentStateMachine.voidCapture(payment, LocalDateTime.now(paymentClock))) {
                        paymentRepository.save(payment);
                    }
                });
                outboxRepository.findById(row.getId()).ifPresent(done -> {
                    done.setSentAt(LocalDateTime.now(paymentClock));
                    done.setLastError(error);
                    outboxRepository.save(done);
                });
            });
            return;
        }
        boolean retryable = e.isTransient();
        log.warn("Could not deliver payment result {} for order {} ({}): {}", row.getRequestId(), row.getOrderId(),
                retryable ? "will retry" : "giving up", error);
        update(row.getId(), failed -> {
            int attempts = retryable ? failed.getAttempts() + 1 : MAX_ATTEMPTS;
            failed.setAttempts(attempts);
            failed.setLastError(error);
            failed.setNextAttemptAt(LocalDateTime.now(paymentClock).plus(backoff(attempts)));
        });
    }

    private void update(Long id, Consumer<PaymentOutboxEntity> change) {
        transaction.executeWithoutResult(status -> outboxRepository.findById(id).ifPresent(row -> {
            change.accept(row);
            outboxRepository.save(row);
        }));
    }

    /** 30 s after the first failure, doubling per attempt, capped at 30 min. */
    static Duration backoff(int attempts) {
        Duration delay = FIRST_BACKOFF.multipliedBy(1L << Math.min(Math.max(attempts - 1, 0), 10));
        return delay.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : delay;
    }

    static String truncate(String value) {
        return value.length() <= ERROR_LENGTH ? value : value.substring(0, ERROR_LENGTH);
    }
}
