package com.awesome.payment.service;

import com.awesome.payment.entity.OrderPaymentLockEntity;
import com.awesome.payment.repository.OrderPaymentLockRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderPaymentLocksTest {

    private final OrderPaymentLockRepository repository = mock(OrderPaymentLockRepository.class);
    private final RecordingTransactionManager transactions = new RecordingTransactionManager();
    private final OrderPaymentLocks locks = new OrderPaymentLocks(repository, transactions);

    @Test
    void anExistingRowIsLockedWithoutInserting() {
        when(repository.findForUpdate(7L)).thenReturn(Optional.of(new OrderPaymentLockEntity(7L)));

        locks.lock(7L);

        verify(repository, never()).saveAndFlush(any());
        assertThat(transactions.propagations).isEmpty();
    }

    @Test
    void aMissingRowIsCreatedInItsOwnTransactionThenLocked() {
        when(repository.findForUpdate(7L)).thenReturn(Optional.empty(), Optional.of(new OrderPaymentLockEntity(7L)));

        locks.lock(7L);

        verify(repository).saveAndFlush(any(OrderPaymentLockEntity.class));
        verify(repository, times(2)).findForUpdate(7L);
        assertThat(transactions.propagations).containsExactly(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Test
    void losingTheInsertRaceStillTakesTheLock() {
        when(repository.findForUpdate(7L)).thenReturn(Optional.empty(), Optional.of(new OrderPaymentLockEntity(7L)));
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));

        locks.lock(7L);

        verify(repository, times(2)).findForUpdate(7L);
    }

    @Test
    void aRowThatNeverAppearsIsAnError() {
        when(repository.findForUpdate(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> locks.lock(7L)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Order payment lock row is missing");
    }

    static class RecordingTransactionManager extends AbstractPlatformTransactionManager {

        private static final long serialVersionUID = 1L;
        final List<Integer> propagations = new ArrayList<>();

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            propagations.add(definition.getPropagationBehavior());
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            // nothing to commit
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            // nothing to roll back
        }
    }
}
