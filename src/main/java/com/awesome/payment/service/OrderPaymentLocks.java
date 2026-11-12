package com.awesome.payment.service;

import com.awesome.payment.repository.OrderPaymentLockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serialises payment creation for the same order across every payment-service instance with
 * a database row lock (same pattern as warehouse-service's {@code OrderLocks}). Orders live in
 * the backend, so the lock is a row in {@code order_payment_locks}. A missing row is created
 * with {@code INSERT ... ON CONFLICT DO NOTHING} in the caller's own transaction (a concurrent
 * creator waits for the first insert to commit and then inserts nothing) and the row is locked
 * with {@code SELECT ... FOR UPDATE} until the caller's transaction ends. Everything happens on
 * the caller's connection: taking the lock never needs a second pooled connection.
 */
@Component
@RequiredArgsConstructor
public class OrderPaymentLocks {

    private final OrderPaymentLockRepository repository;

    /** Blocks until the caller's transaction holds the lock for this order. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(long orderId) {
        if (repository.findForUpdate(orderId).isPresent()) {
            return;
        }
        repository.insertIfAbsent(orderId);
        repository.findForUpdate(orderId).orElseThrow(() -> new IllegalStateException("Order payment lock row is missing"));
    }
}
