package com.awesome.payment.dto.refund;

import com.awesome.payment.entity.PaymentStatus;
import com.awesome.payment.entity.RefundEntity;

import java.math.BigDecimal;
import java.util.UUID;

/** A booked card refund; {@code status} is the payment's status right after it. */
public record RefundResponseDto(UUID refundId, UUID paymentId, BigDecimal amount, PaymentStatus status) {

    public static RefundResponseDto from(RefundEntity refund) {
        return new RefundResponseDto(refund.getId(), refund.getPaymentId(), refund.getAmount(),
                refund.getPaymentStatus());
    }
}
