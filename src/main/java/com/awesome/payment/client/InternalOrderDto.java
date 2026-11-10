package com.awesome.payment.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/**
 * The fields payment-service reads from {@code GET /internal/v1/orders/{id}}; the backend
 * sends more (items, address, fulfilment state), which are ignored.
 *
 * @param amountDue what the customer still has to pay by card (total minus gift card and
 *                  loyalty points); older backends omit it and {@code totalAmount} applies
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InternalOrderDto(
        Long id,
        String username,
        OrderStatus status,
        BigDecimal totalAmount,
        BigDecimal amountDue) {

    /** The amount to charge: {@code amountDue} when present, else {@code totalAmount}. */
    public BigDecimal payableAmount() {
        return amountDue == null ? totalAmount : amountDue;
    }
}
