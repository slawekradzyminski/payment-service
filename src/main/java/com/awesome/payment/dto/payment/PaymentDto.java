package com.awesome.payment.dto.payment;

import com.awesome.payment.entity.CardBrand;
import com.awesome.payment.entity.PaymentEntity;
import com.awesome.payment.entity.PaymentStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "A card payment", requiredProperties = {"id", "orderId", "status", "amount", "currency",
        "cardBrand", "cardLast4", "refundedAmount", "createdAt", "updatedAt"})
public record PaymentDto(
        UUID id,
        Long orderId,
        PaymentStatus status,
        @Schema(example = "126.00") BigDecimal amount,
        @Schema(example = "USD") String currency,
        CardBrand cardBrand,
        @Schema(example = "4242") String cardLast4,
        @Schema(description = "CARD_DECLINED, INSUFFICIENT_FUNDS, THREE_DS_FAILED, PROVIDER_TIMEOUT or "
                + "ORDER_NOT_PAYABLE (voided); null otherwise", nullable = true) String failureCode,
        @Schema(nullable = true) String failureMessage,
        @Schema(description = "Present while the payment is REQUIRES_ACTION", nullable = true) NextActionDto nextAction,
        @Schema(example = "0.00") BigDecimal refundedAmount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static PaymentDto from(PaymentEntity payment) {
        return new PaymentDto(
                payment.getId(),
                payment.getOrderId(),
                payment.getStatus(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getCardBrand(),
                payment.getCardLast4(),
                payment.getFailureCode(),
                payment.getFailureMessage(),
                payment.getStatus() == PaymentStatus.REQUIRES_ACTION ? NextActionDto.threeDsChallenge() : null,
                payment.getRefundedAmount(),
                payment.getCreatedAt(),
                payment.getUpdatedAt());
    }
}
