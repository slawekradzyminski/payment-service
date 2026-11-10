package com.awesome.payment.service;

import com.awesome.payment.controller.exception.CustomException;
import com.awesome.payment.entity.PaymentEntity;
import com.awesome.payment.entity.PaymentStatus;
import com.awesome.payment.entity.Settlement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentStateMachineTest {

    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 11, 10, 12, 0);
    private static final LocalDateTime NOW = CREATED.plusMinutes(1);

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"REQUIRES_ACTION", "PROCESSING"})
    void pendingPaymentsCanBeCapturedAndTheSettlementIsCleared(PaymentStatus status) {
        PaymentEntity payment = payment(status, "10.00", "0.00");

        PaymentStateMachine.capture(payment, NOW);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(payment.getSettlement()).isNull();
        assertThat(payment.getSettleAt()).isNull();
        assertThat(payment.getUpdatedAt()).isEqualTo(NOW);
        assertThat(payment.getFailureCode()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"REQUIRES_ACTION", "PROCESSING"})
    void pendingPaymentsCanFail(PaymentStatus status) {
        PaymentEntity payment = payment(status, "10.00", "0.00");

        PaymentStateMachine.fail(payment, "CODE", "Message", NOW);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureCode()).isEqualTo("CODE");
        assertThat(payment.getFailureMessage()).isEqualTo("Message");
        assertThat(payment.getSettleAt()).isNull();
        assertThat(payment.getSettlement()).isNull();
        assertThat(payment.getUpdatedAt()).isEqualTo(NOW);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"CAPTURED", "FAILED", "PARTIALLY_REFUNDED", "REFUNDED", "VOIDED"})
    void finishedPaymentsCannotBeCapturedOrFailedAgain(PaymentStatus status) {
        PaymentEntity payment = payment(status, "10.00", "0.00");

        assertConflict(() -> PaymentStateMachine.capture(payment, NOW), "Payment is " + status);
        assertConflict(() -> PaymentStateMachine.fail(payment, "X", "Y", NOW), "Payment is " + status);
        assertThat(payment.getStatus()).isEqualTo(status);
        assertThat(payment.getUpdatedAt()).isEqualTo(CREATED);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"CAPTURED", "PARTIALLY_REFUNDED"})
    void capturesAreVoidedWithTheWholeAmountReturned(PaymentStatus status) {
        PaymentEntity payment = payment(status, "40.00", "5.00");

        assertThat(PaymentStateMachine.voidCapture(payment, NOW)).isTrue();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.VOIDED);
        assertThat(payment.getRefundedAmount()).isEqualByComparingTo("40.00");
        assertThat(payment.getFailureCode()).isEqualTo("ORDER_NOT_PAYABLE");
        assertThat(payment.getFailureMessage()).isNotBlank();
        assertThat(payment.getUpdatedAt()).isEqualTo(NOW);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"REQUIRES_ACTION", "PROCESSING", "FAILED", "REFUNDED", "VOIDED"})
    void otherStatusesAreNotVoided(PaymentStatus status) {
        PaymentEntity payment = payment(status, "40.00", "0.00");

        assertThat(PaymentStateMachine.voidCapture(payment, NOW)).isFalse();

        assertThat(payment.getStatus()).isEqualTo(status);
        assertThat(payment.getRefundedAmount()).isEqualByComparingTo("0.00");
        assertThat(payment.getUpdatedAt()).isEqualTo(CREATED);
    }

    @Test
    void partialThenFullRefund() {
        PaymentEntity payment = payment(PaymentStatus.CAPTURED, "100.00", "0.00");

        assertThat(PaymentStateMachine.refund(payment, new BigDecimal("30"), NOW))
                .isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(payment.getRefundedAmount()).isEqualByComparingTo("30.00");
        assertThat(payment.getUpdatedAt()).isEqualTo(NOW);
        assertThat(PaymentStateMachine.refundable(payment)).isEqualByComparingTo("70.00");

        assertThat(PaymentStateMachine.refund(payment, new BigDecimal("70.00"), NOW)).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.getRefundedAmount()).isEqualByComparingTo("100.00");
        assertThat(PaymentStateMachine.refundable(payment)).isEqualByComparingTo("0.00");
    }

    @Test
    void refundingExactlyTheRestIsAllowedButOneCentMoreIsNot() {
        PaymentEntity payment = payment(PaymentStatus.PARTIALLY_REFUNDED, "100.00", "99.99");

        assertConflict(() -> PaymentStateMachine.refund(payment, new BigDecimal("0.02"), NOW),
                "Refund exceeds the refundable amount");
        assertThat(payment.getRefundedAmount()).isEqualByComparingTo("99.99");
        assertThat(PaymentStateMachine.refund(payment, new BigDecimal("0.01"), NOW)).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void zeroOrNegativeRefundsAreRejected() {
        PaymentEntity payment = payment(PaymentStatus.CAPTURED, "100.00", "0.00");

        assertConflict(() -> PaymentStateMachine.refund(payment, BigDecimal.ZERO, NOW),
                "Refund exceeds the refundable amount");
        assertConflict(() -> PaymentStateMachine.refund(payment, new BigDecimal("-1"), NOW),
                "Refund exceeds the refundable amount");
        assertConflict(() -> PaymentStateMachine.refund(payment, new BigDecimal("0.004"), NOW),
                "Refund exceeds the refundable amount");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"REQUIRES_ACTION", "PROCESSING", "FAILED", "VOIDED"})
    void uncapturedPaymentsHaveNothingRefundable(PaymentStatus status) {
        PaymentEntity payment = payment(status, "100.00", "0.00");

        assertThat(PaymentStateMachine.refundable(payment)).isEqualByComparingTo("0.00");
        assertConflict(() -> PaymentStateMachine.refund(payment, BigDecimal.ONE, NOW),
                "Refund exceeds the refundable amount");
    }

    @Test
    void refundableIsRoundedToCents() {
        assertThat(PaymentStateMachine.refundable(payment(PaymentStatus.CAPTURED, "10", "0")).scale()).isEqualTo(2);
        assertThat(PaymentStateMachine.refundable(payment(PaymentStatus.FAILED, "10", "0")).scale()).isEqualTo(2);
    }

    @Test
    void blockingAndCapturedSetsMatchTheContract() {
        assertThat(PaymentStateMachine.BLOCKING).containsExactlyInAnyOrder(PaymentStatus.REQUIRES_ACTION,
                PaymentStatus.PROCESSING, PaymentStatus.CAPTURED, PaymentStatus.PARTIALLY_REFUNDED, PaymentStatus.REFUNDED);
        assertThat(PaymentStateMachine.CAPTURED_STATES).containsExactlyInAnyOrder(PaymentStatus.CAPTURED,
                PaymentStatus.PARTIALLY_REFUNDED, PaymentStatus.REFUNDED);
    }

    private static PaymentEntity payment(PaymentStatus status, String amount, String refunded) {
        return PaymentEntity.builder()
                .status(status)
                .amount(new BigDecimal(amount))
                .refundedAmount(new BigDecimal(refunded))
                .settlement(Settlement.CAPTURE)
                .settleAt(CREATED.plusSeconds(10))
                .createdAt(CREATED)
                .updatedAt(CREATED)
                .build();
    }

    private static void assertConflict(Runnable action, String message) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(CustomException.class, e -> {
            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(e.getMessage()).isEqualTo(message);
        });
    }
}
