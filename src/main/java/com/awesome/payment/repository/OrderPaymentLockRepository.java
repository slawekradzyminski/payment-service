package com.awesome.payment.repository;

import com.awesome.payment.entity.OrderPaymentLockEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface OrderPaymentLockRepository extends JpaRepository<OrderPaymentLockEntity, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from OrderPaymentLockEntity l where l.orderId = :orderId")
    Optional<OrderPaymentLockEntity> findForUpdate(@Param("orderId") Long orderId);

    /**
     * Creates the order's lock row unless it exists; returns the number of inserted rows. A
     * concurrent uncommitted insert of the same order makes this wait for that transaction
     * instead of failing, so the caller's transaction stays usable.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "insert into {h-schema}order_payment_locks (order_id) values (:orderId) on conflict do nothing",
            nativeQuery = true)
    int insertIfAbsent(@Param("orderId") long orderId);
}
