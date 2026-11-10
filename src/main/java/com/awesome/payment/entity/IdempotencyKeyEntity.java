package com.awesome.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/** An {@code Idempotency-Key} a caller used to create a payment, with a fingerprint of the request body. */
@Entity
@Table(name = "payment_idempotency_keys")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IdempotencyKeyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String username;

    @Column(name = "idempotency_key", nullable = false, length = 64)
    private String idempotencyKey;

    /** Hex HMAC-SHA256 of the canonical request; never the card number itself. */
    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
