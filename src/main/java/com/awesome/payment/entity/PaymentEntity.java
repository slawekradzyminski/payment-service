package com.awesome.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/** A card payment for one backend order. Only the card brand and last four digits are kept. */
@Entity
@Table(name = "payments")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentEntity {

    @Id
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(nullable = false)
    private String username;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PaymentStatus status;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "refunded_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal refundedAmount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "card_brand", nullable = false, length = 16)
    private CardBrand cardBrand;

    @Column(name = "card_last4", nullable = false, length = 4)
    private String cardLast4;

    @Column(name = "failure_code", length = 32)
    private String failureCode;

    @Column(name = "failure_message")
    private String failureMessage;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private Settlement settlement;

    @Column(name = "settle_at")
    private LocalDateTime settleAt;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
