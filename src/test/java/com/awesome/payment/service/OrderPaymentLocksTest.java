package com.awesome.payment.service;

import com.awesome.payment.entity.OrderPaymentLockEntity;
import com.awesome.payment.repository.OrderPaymentLockRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderPaymentLocksTest {

    private final OrderPaymentLockRepository repository = mock(OrderPaymentLockRepository.class);
    private final OrderPaymentLocks locks = new OrderPaymentLocks(repository);

    @Test
    void anExistingRowIsLockedWithoutInserting() {
        when(repository.findForUpdate(7L)).thenReturn(Optional.of(new OrderPaymentLockEntity(7L)));

        locks.lock(7L);

        verify(repository, never()).insertIfAbsent(anyLong());
    }

    @Test
    void aMissingRowIsInsertedOnTheCallersConnectionThenLocked() {
        when(repository.findForUpdate(7L)).thenReturn(Optional.empty(), Optional.of(new OrderPaymentLockEntity(7L)));
        when(repository.insertIfAbsent(7L)).thenReturn(1);

        locks.lock(7L);

        InOrder order = inOrder(repository);
        order.verify(repository).findForUpdate(7L);
        order.verify(repository).insertIfAbsent(7L);
        order.verify(repository).findForUpdate(7L);
        verify(repository, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void losingTheInsertRaceStillTakesTheLock() {
        when(repository.findForUpdate(7L)).thenReturn(Optional.empty(), Optional.of(new OrderPaymentLockEntity(7L)));
        when(repository.insertIfAbsent(7L)).thenReturn(0);

        locks.lock(7L);

        InOrder order = inOrder(repository);
        order.verify(repository).insertIfAbsent(7L);
        order.verify(repository).findForUpdate(7L);
    }

    @Test
    void aRowThatNeverAppearsIsAnError() {
        when(repository.findForUpdate(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> locks.lock(7L)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Order payment lock row is missing");
    }
}
