package com.awesome.payment.service;

import com.awesome.payment.entity.PaymentEntity;
import com.awesome.payment.entity.PaymentStatus;
import com.awesome.payment.entity.RefundEntity;
import com.awesome.payment.repository.PaymentRepository;
import com.awesome.payment.repository.RefundRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Card refunds the backend requested ({@code card-refund.requested} feed events). Idempotent per
 * requestId: a repeated request books nothing and reports the first refund. An order without a
 * captured card payment, or an amount above the refundable rest, books nothing either. None of
 * these outcomes throws, so the caller's transaction (the feed event) always commits.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundService {

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final Clock paymentClock;

    /** A refund the backend asks for; {@code requestId} is the backend's idempotency key. */
    public record CardRefundRequest(UUID requestId, long orderId, BigDecimal amount, String reason) {
    }

    /** What happened to a request. {@code refund} is set for BOOKED and ALREADY_BOOKED. */
    public record RefundOutcome(Result result, RefundEntity refund) {

        public enum Result {
            BOOKED,
            ALREADY_BOOKED,
            NO_CAPTURED_PAYMENT,
            EXCEEDS_REFUNDABLE
        }
    }

    @Transactional
    public RefundOutcome refund(CardRefundRequest request) {
        Optional<RefundEntity> earlier = refundRepository.findByRequestId(request.requestId());
        if (earlier.isPresent()) {
            log.info("Refund request {} was already booked as refund {}", request.requestId(), earlier.get().getId());
            return new RefundOutcome(RefundOutcome.Result.ALREADY_BOOKED, earlier.get());
        }
        Optional<PaymentEntity> captured = paymentRepository.findByOrderIdAndStatusInOrderByCreatedAtDesc(
                request.orderId(), PaymentStateMachine.CAPTURED_STATES).stream().findFirst();
        if (captured.isEmpty()) {
            log.info("Refund request {}: order {} has no captured card payment, nothing to refund",
                    request.requestId(), request.orderId());
            return new RefundOutcome(RefundOutcome.Result.NO_CAPTURED_PAYMENT, null);
        }
        PaymentEntity payment = captured.get();
        BigDecimal amount = request.amount().setScale(2, RoundingMode.HALF_UP);
        if (amount.signum() <= 0 || amount.compareTo(PaymentStateMachine.refundable(payment)) > 0) {
            log.warn("Refund request {}: {} exceeds the refundable {} of payment {} (order {}); not refunded",
                    request.requestId(), amount, PaymentStateMachine.refundable(payment), payment.getId(),
                    request.orderId());
            return new RefundOutcome(RefundOutcome.Result.EXCEEDS_REFUNDABLE, null);
        }
        LocalDateTime now = LocalDateTime.now(paymentClock);
        PaymentStatus status = PaymentStateMachine.refund(payment, amount, now);
        paymentRepository.save(payment);
        RefundEntity refund = refundRepository.save(RefundEntity.builder()
                .id(UUID.randomUUID())
                .requestId(request.requestId())
                .paymentId(payment.getId())
                .orderId(request.orderId())
                .amount(amount)
                .reason(request.reason())
                .paymentStatus(status)
                .createdAt(now)
                .build());
        log.info("Refunded {} of payment {} for order {}: {}", refund.getAmount(), payment.getId(),
                payment.getOrderId(), status);
        return new RefundOutcome(RefundOutcome.Result.BOOKED, refund);
    }
}
