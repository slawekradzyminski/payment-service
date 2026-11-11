package com.awesome.payment.service.notification;

import com.awesome.payment.client.PaymentResultRequest;
import com.awesome.payment.correlation.Correlation;
import com.awesome.payment.entity.PaymentEntity;
import com.awesome.payment.entity.PaymentOutboxEntity;
import com.awesome.payment.entity.PaymentStatus;
import com.awesome.payment.repository.PaymentOutboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Writes payment results in the caller's transaction, so a result exists exactly when the
 * payment change that produced it is committed. {@link PaymentResultDispatcher} sends them
 * after the commit and retries failures. Each row keeps the correlation ids of the request that
 * created the payment (or, for a payment without them, of the current request), so the
 * notification carries them even when the settlement job finishes the payment later.
 */
@Component
@RequiredArgsConstructor
public class PaymentResultOutbox {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final PaymentOutboxRepository repository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock paymentClock;

    /** Published after an enqueue; the dispatcher sends right after the commit. */
    public record Enqueued() {
    }

    /**
     * Queues the final result (CAPTURED or FAILED) of {@code payment} {@code copies} times under one
     * requestId; more than one copy is the duplicate-delivery demo card.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID enqueue(PaymentEntity payment, int copies) {
        if (payment.getStatus() != PaymentStatus.CAPTURED && payment.getStatus() != PaymentStatus.FAILED) {
            throw new IllegalArgumentException("Only CAPTURED or FAILED results are sent, not " + payment.getStatus());
        }
        UUID requestId = UUID.randomUUID();
        String payload = JSON.writeValueAsString(new PaymentResultRequest(requestId, payment.getId(),
                payment.getStatus().name(), payment.getAmount(), payment.getCurrency(), payment.getFailureCode()));
        LocalDateTime now = LocalDateTime.now(paymentClock);
        boolean ownIds = payment.getCorrelationRequestId() != null || payment.getTraceparent() != null;
        String correlationRequestId = ownIds ? payment.getCorrelationRequestId() : Correlation.currentRequestId();
        String traceparent = ownIds ? payment.getTraceparent() : Correlation.currentTraceparent();
        for (int i = 0; i < copies; i++) {
            repository.save(PaymentOutboxEntity.builder()
                    .requestId(requestId)
                    .paymentId(payment.getId())
                    .orderId(payment.getOrderId())
                    .payload(payload)
                    .correlationRequestId(correlationRequestId)
                    .traceparent(traceparent)
                    .attempts(0)
                    .nextAttemptAt(now)
                    .createdAt(now)
                    .build());
        }
        eventPublisher.publishEvent(new Enqueued());
        return requestId;
    }
}
