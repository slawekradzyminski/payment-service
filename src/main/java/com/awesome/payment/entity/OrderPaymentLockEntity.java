package com.awesome.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Lock row for one order; see {@code OrderPaymentLocks}. */
@Entity
@Table(name = "order_payment_locks")
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class OrderPaymentLockEntity {

    @Id
    @Column(name = "order_id")
    private Long orderId;
}
