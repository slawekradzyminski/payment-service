package com.awesome.payment.dto.refund;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/** Body of {@code POST /internal/v1/payments/refunds}; the backend retries with the same requestId. */
public record RefundRequestDto(
        @NotNull(message = "Request id is required") UUID requestId,
        @NotNull(message = "Order id is required") Long orderId,
        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Amount must be at least 0.01")
        @Digits(integer = 10, fraction = 2, message = "Amount must have at most two decimal places")
        BigDecimal amount,
        @Size(max = 255, message = "Reason must be at most 255 characters") String reason) {
}
