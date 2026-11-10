package com.awesome.payment.client;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Body of {@code POST /internal/v1/orders/{orderId}/payment}. The backend handles each
 * {@code requestId} once and answers a replay with the same result.
 *
 * @param status {@code CAPTURED} or {@code FAILED}
 */
@JsonPropertyOrder({"requestId", "paymentId", "status", "amount", "currency", "failureCode"})
public record PaymentResultRequest(
        UUID requestId,
        UUID paymentId,
        String status,
        BigDecimal amount,
        String currency,
        String failureCode) {
}
