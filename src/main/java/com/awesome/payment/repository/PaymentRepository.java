package com.awesome.payment.repository;

import com.awesome.payment.entity.PaymentEntity;
import com.awesome.payment.entity.PaymentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<PaymentEntity, UUID> {

    List<PaymentEntity> findByOrderIdOrderByCreatedAtDescIdDesc(Long orderId);

    boolean existsByOrderIdAndStatusIn(Long orderId, Collection<PaymentStatus> statuses);

    /** The order's card payment that refunds go to (at most one exists; newest first just in case). */
    List<PaymentEntity> findByOrderIdAndStatusInOrderByCreatedAtDesc(Long orderId, Collection<PaymentStatus> statuses);

    Page<PaymentEntity> findByStatus(PaymentStatus status, Pageable pageable);

    /** PROCESSING payments whose simulated settlement is due, oldest first. */
    @Query("""
            select p from PaymentEntity p
            where p.status = com.awesome.payment.entity.PaymentStatus.PROCESSING and p.settleAt <= :now
            order by p.settleAt""")
    List<PaymentEntity> findDueForSettlement(@Param("now") LocalDateTime now, Pageable pageable);
}
