package com.awesome.payment.service;

import com.awesome.payment.controller.exception.CustomException;
import com.awesome.payment.entity.PaymentEntity;
import com.awesome.payment.entity.PaymentStatus;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

/**
 * Allowed payment transitions:
 * <pre>
 * REQUIRES_ACTION ─┬─► CAPTURED ─┬─► PARTIALLY_REFUNDED ─┬─► REFUNDED
 * PROCESSING ──────┤             │                       │
 *                  └─► FAILED    └─► VOIDED ◄────────────┘ (backend: order not payable)
 * </pre>
 * Every change goes through this class, which rejects anything else.
 */
public final class PaymentStateMachine {

    public static final String ORDER_NOT_PAYABLE = "ORDER_NOT_PAYABLE";

    /** Statuses that keep a second payment for the same order from starting. */
    public static final Set<PaymentStatus> BLOCKING = Set.copyOf(EnumSet.of(PaymentStatus.REQUIRES_ACTION,
            PaymentStatus.PROCESSING, PaymentStatus.CAPTURED, PaymentStatus.PARTIALLY_REFUNDED, PaymentStatus.REFUNDED));

    /** Statuses of a payment whose captured money is (or was) on the card. */
    public static final Set<PaymentStatus> CAPTURED_STATES = Set.copyOf(EnumSet.of(PaymentStatus.CAPTURED,
            PaymentStatus.PARTIALLY_REFUNDED, PaymentStatus.REFUNDED));

    private static final Set<PaymentStatus> PENDING = Set.copyOf(EnumSet.of(PaymentStatus.REQUIRES_ACTION,
            PaymentStatus.PROCESSING));
    private static final Set<PaymentStatus> VOIDABLE = Set.copyOf(EnumSet.of(PaymentStatus.CAPTURED,
            PaymentStatus.PARTIALLY_REFUNDED));

    private PaymentStateMachine() {
    }

    public static void capture(PaymentEntity payment, LocalDateTime now) {
        require(payment, PENDING);
        payment.setStatus(PaymentStatus.CAPTURED);
        finishPending(payment, now);
    }

    public static void fail(PaymentEntity payment, String failureCode, String failureMessage, LocalDateTime now) {
        require(payment, PENDING);
        payment.setStatus(PaymentStatus.FAILED);
        payment.setFailureCode(failureCode);
        payment.setFailureMessage(failureMessage);
        finishPending(payment, now);
    }

    /** A REQUIRES_ACTION payment whose 3-D Secure deadline has passed. */
    public static boolean isThreeDsExpired(PaymentEntity payment, LocalDateTime now) {
        return payment.getStatus() == PaymentStatus.REQUIRES_ACTION && payment.getSettleAt() != null
                && !payment.getSettleAt().isAfter(now);
    }

    /**
     * The backend refused a capture because the order is no longer payable: the money goes back
     * to the card, so the whole amount counts as returned.
     *
     * @return false when the payment is not in a voidable state (nothing changed)
     */
    public static boolean voidCapture(PaymentEntity payment, LocalDateTime now) {
        if (!VOIDABLE.contains(payment.getStatus())) {
            return false;
        }
        payment.setStatus(PaymentStatus.VOIDED);
        payment.setRefundedAmount(payment.getAmount());
        payment.setFailureCode(ORDER_NOT_PAYABLE);
        payment.setFailureMessage("The order was no longer payable; the capture was voided");
        payment.setUpdatedAt(now);
        return true;
    }

    /** What can still be refunded to the card. */
    public static BigDecimal refundable(PaymentEntity payment) {
        if (!CAPTURED_STATES.contains(payment.getStatus())) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return payment.getAmount().subtract(payment.getRefundedAmount()).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Books a card refund.
     *
     * @throws CustomException 409 when the amount exceeds {@link #refundable(PaymentEntity)}
     */
    public static PaymentStatus refund(PaymentEntity payment, BigDecimal amount, LocalDateTime now) {
        BigDecimal scaled = amount.setScale(2, RoundingMode.HALF_UP);
        if (scaled.signum() <= 0 || scaled.compareTo(refundable(payment)) > 0) {
            throw new CustomException("Refund exceeds the refundable amount", HttpStatus.CONFLICT);
        }
        BigDecimal refunded = payment.getRefundedAmount().add(scaled);
        payment.setRefundedAmount(refunded);
        payment.setStatus(refunded.compareTo(payment.getAmount()) == 0
                ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED);
        payment.setUpdatedAt(now);
        return payment.getStatus();
    }

    private static void finishPending(PaymentEntity payment, LocalDateTime now) {
        payment.setSettlement(null);
        payment.setSettleAt(null);
        payment.setUpdatedAt(now);
    }

    private static void require(PaymentEntity payment, Set<PaymentStatus> allowed) {
        if (!allowed.contains(payment.getStatus())) {
            throw new CustomException("Payment is " + payment.getStatus(), HttpStatus.CONFLICT);
        }
    }
}
