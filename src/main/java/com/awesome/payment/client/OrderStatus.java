package com.awesome.payment.client;

/** Backend order status; only PENDING orders are payable. */
public enum OrderStatus {
    PENDING,
    PAID,
    SHIPPED,
    DELIVERED,
    CANCELLED
}
