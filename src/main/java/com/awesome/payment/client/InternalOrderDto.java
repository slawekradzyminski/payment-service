package com.awesome.payment.client;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The fields payment-service reads from {@code GET /internal/v1/orders/{id}}; the backend
 * sends more (items, address, fulfilment state), which are ignored.
 *
 * @param amountDue what the customer still has to pay by card (total minus gift card and
 *                  loyalty points); older backends omit it and {@code totalAmount} applies
 * @param reservationExpiresAt when the order's stock reservation runs out (backend 3.21.0+); the
 *                  backend then cancels the order. Null for orders without a time limit and from
 *                  older backends: such orders never expire here
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InternalOrderDto(
        Long id,
        String username,
        OrderStatus status,
        BigDecimal totalAmount,
        BigDecimal amountDue,
        Instant reservationExpiresAt) {

    @JsonCreator
    public InternalOrderDto {
        // canonical constructor: the one Jackson binds the order snapshot to
    }

    /** An order without a reservation time limit (older backends, reservations disabled). */
    public InternalOrderDto(Long id, String username, OrderStatus status, BigDecimal totalAmount,
                            BigDecimal amountDue) {
        this(id, username, status, totalAmount, amountDue, null);
    }

    /** The amount to charge: {@code amountDue} when present, else {@code totalAmount}. */
    public BigDecimal payableAmount() {
        return amountDue == null ? totalAmount : amountDue;
    }

    /** True when the reservation has a time limit that is not after {@code now} (the backend expires at {@code <=}). */
    public boolean reservationExpired(Instant now) {
        return reservationExpiresAt != null && !reservationExpiresAt.isAfter(now);
    }
}
