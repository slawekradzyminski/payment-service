package com.awesome.payment.service.notification;

import com.awesome.payment.client.BackendClient;
import com.awesome.payment.client.BackendException;
import com.awesome.payment.correlation.Correlation;
import com.awesome.payment.entity.PaymentOutboxEntity;
import com.awesome.payment.repository.PaymentOutboxRepository;
import com.awesome.payment.repository.PaymentRepository;
import com.awesome.payment.service.PaymentStateMachine;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PreDestroy;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Delivers outbox payment results to the backend: right after the transaction that wrote them
 * commits, and again on a schedule for those that failed. The after-commit delivery runs on the
 * dispatcher's own worker thread, never on the committing (request, settlement or poller) thread:
 * that thread still holds its pooled connection until the commit completes, so sending there
 * would need a second connection and keep the first one busy during the HTTP call. Triggers that
 * arrive while a run is still queued are coalesced into it; a lost trigger (shutdown, failure) is
 * covered by the scheduled retry, so delivery stays at least once. No response or a 5xx is retried with
 * exponential backoff (30 s doubling to 30 min, at most {@value #MAX_ATTEMPTS} attempts). A 409
 * ("Order is not payable") to a capture voids the payment. Any other rejection is final. The
 * backend handles each requestId once, so a retry after a lost response is harmless. Each delivery
 * runs (and logs) under the correlation ids stored with the row, so the backend receives the
 * original {@code X-Request-Id} and a child of the original trace.
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
    /** One worker: after-commit deliveries run here, in order, off the committing thread. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("payment-result-dispatch").daemon().factory());
    /** True while a triggered run is queued and has not started reading the outbox yet. */
    private final AtomicBoolean queued = new AtomicBoolean();

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
        trigger();
    }

    /**
     * Queues a run of {@link #dispatchDue()} on the worker unless one is already queued (that run
     * has not read the outbox yet, so it also sends what was just committed).
     */
    void trigger() {
        if (!queued.compareAndSet(false, true)) {
            return;
        }
        try {
            worker.execute(this::runTriggered);
        } catch (RejectedExecutionException e) {
            queued.set(false);
            log.warn("Payment result dispatcher is shut down; the scheduled retry delivers the result");
        }
    }

    private void runTriggered() {
        queued.set(false);
        try {
            dispatchDue();
        } catch (RuntimeException e) {
            log.warn("Could not dispatch payment results; the scheduled retry sends them", e);
        }
    }

    /**
     * Waits until every run triggered so far has finished; false when {@code timeout} passed first.
     * Used by tests that assert on deliveries right after a payment changed.
     */
    public boolean awaitTriggered(Duration timeout) {
        try {
            worker.submit(() -> { }).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException | ExecutionException | RejectedExecutionException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @PreDestroy
    void shutdown() throws InterruptedException {
        worker.shutdown();
        if (!worker.awaitTermination(5, TimeUnit.SECONDS)) {
            worker.shutdownNow();
        }
    }

    @Scheduled(fixedDelayString = "${payment.notifications.retry-interval:PT30S}",
            initialDelayString = "${payment.notifications.retry-interval:PT30S}")
    public void retryDue() {
        if (retryEnabled) {
            dispatchDue();
        }
    }

    /**
     * Sends every due result; synchronized so one instance never sends a row twice at once. Callers
     * must not hold a database connection (no surrounding transaction): a thread waiting for the
     * monitor, or for the backend's answer, then never keeps a pooled connection busy.
     */
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
        try (Correlation.Scope ignored = correlation(row)) {
            return deliver(row);
        }
    }

    /** The row's ids; a row without any (written before 1.2.0) keeps the current trace. */
    private static Correlation.Scope correlation(PaymentOutboxEntity row) {
        if (row.getCorrelationRequestId() == null && row.getTraceparent() == null) {
            return Correlation.open(Correlation.currentRequestId(), Correlation.currentTraceId());
        }
        return Correlation.open(row.getCorrelationRequestId(), Correlation.traceIdOf(row.getTraceparent()));
    }

    private boolean deliver(PaymentOutboxEntity row) {
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
