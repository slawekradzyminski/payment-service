package com.awesome.payment.service;

import com.awesome.payment.entity.OrderPaymentLockEntity;
import com.awesome.payment.repository.OrderPaymentLockRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Serialises payment creation for the same order across every payment-service instance with
 * a database row lock (same pattern as warehouse-service's {@code OrderLocks}). Orders live in
 * the backend, so the lock is a row in {@code order_payment_locks}: it is created once (in its
 * own transaction, so a concurrent creator merely loses the insert) and then locked with
 * {@code SELECT ... FOR UPDATE} until the caller's transaction ends.
 */
@Slf4j
@Component
public class OrderPaymentLocks {

    private final OrderPaymentLockRepository repository;
    private final TransactionTemplate newTransaction;

    public OrderPaymentLocks(OrderPaymentLockRepository repository, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Blocks until the caller's transaction holds the lock for this order. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(long orderId) {
        if (repository.findForUpdate(orderId).isPresent()) {
            return;
        }
        try {
            newTransaction.executeWithoutResult(status -> repository.saveAndFlush(new OrderPaymentLockEntity(orderId)));
        } catch (DataIntegrityViolationException e) {
            log.debug("Order {} payment lock row was created concurrently; waiting for it", orderId);
        }
        repository.findForUpdate(orderId).orElseThrow(() -> new IllegalStateException("Order payment lock row is missing"));
    }
}
