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

    /** The order's payments in {@code status} whose deadline has passed. */
    List<PaymentEntity> findByOrderIdAndStatusAndSettleAtLessThanEqual(Long orderId, PaymentStatus status,
                                                                     LocalDateTime now);

    Page<PaymentEntity> findByStatus(PaymentStatus status, Pageable pageable);

    /**
     * Payments whose deadline has passed, oldest first: PROCESSING ones settle, REQUIRES_ACTION
     * ones expire (3-D Secure not completed in time).
     */
    @Query("""
            select p from PaymentEntity p
            where p.status in (com.awesome.payment.entity.PaymentStatus.PROCESSING,
                               com.awesome.payment.entity.PaymentStatus.REQUIRES_ACTION)
              and p.settleAt <= :now
            order by p.settleAt""")
    List<PaymentEntity> findDueForSettlement(@Param("now") LocalDateTime now, Pageable pageable);
}
