package com.awesome.payment.entity;

/** Lifecycle of a card payment; see {@code PaymentStateMachine} for the allowed transitions. */
public enum PaymentStatus {
    REQUIRES_ACTION,
    PROCESSING,
    CAPTURED,
    FAILED,
    PARTIALLY_REFUNDED,
    REFUNDED,
    VOIDED
}
