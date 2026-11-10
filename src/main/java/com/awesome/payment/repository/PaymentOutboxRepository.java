package com.awesome.payment.repository;

import com.awesome.payment.entity.PaymentOutboxEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface PaymentOutboxRepository extends JpaRepository<PaymentOutboxEntity, Long> {

    /** Undelivered results whose next attempt is due, oldest first. */
    @Query("""
            select o from PaymentOutboxEntity o
            where o.sentAt is null and o.attempts < :maxAttempts and o.nextAttemptAt <= :now
            order by o.id""")
    List<PaymentOutboxEntity> findDue(@Param("now") LocalDateTime now, @Param("maxAttempts") int maxAttempts,
                                      Pageable pageable);

    List<PaymentOutboxEntity> findByPaymentIdOrderById(UUID paymentId);
}
