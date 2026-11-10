package com.awesome.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/** A card refund the backend booked; {@code requestId} makes it idempotent. */
@Entity
@Table(name = "refunds")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefundEntity {

    @Id
    private UUID id;

    @Column(name = "request_id", nullable = false, unique = true)
    private UUID requestId;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    private String reason;

    /** Payment status right after this refund (PARTIALLY_REFUNDED or REFUNDED). */
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, length = 32)
    private PaymentStatus paymentStatus;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
