package com.awesome.payment.repository;

import com.awesome.payment.entity.OrderPaymentLockEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface OrderPaymentLockRepository extends JpaRepository<OrderPaymentLockEntity, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from OrderPaymentLockEntity l where l.orderId = :orderId")
    Optional<OrderPaymentLockEntity> findForUpdate(@Param("orderId") Long orderId);
}
