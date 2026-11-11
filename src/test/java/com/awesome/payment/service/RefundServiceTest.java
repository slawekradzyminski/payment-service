package com.awesome.payment.service;

import com.awesome.payment.dto.payment.PaymentDto;
import com.awesome.payment.entity.PaymentStatus;
import com.awesome.payment.service.RefundService.CardRefundRequest;
import com.awesome.payment.service.RefundService.RefundOutcome;
import com.awesome.payment.service.RefundService.RefundOutcome.Result;
import com.awesome.payment.support.AbstractFlowTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The outcome of each refund request, which the feed handler only logs. */
class RefundServiceTest extends AbstractFlowTest {

    @Autowired
    private RefundService refundService;

    @Test
    void eachOutcomeIsReported() {
        PaymentDto payment = paid(VISA);
        UUID requestId = UUID.randomUUID();

        RefundOutcome booked = refundService.refund(request(requestId, payment.orderId(), "126.00"));
        assertThat(booked.result()).isEqualTo(Result.BOOKED);
        assertThat(booked.refund().getPaymentId()).isEqualTo(payment.id());
        assertThat(booked.refund().getPaymentStatus()).isEqualTo(PaymentStatus.REFUNDED);

        RefundOutcome again = refundService.refund(request(requestId, payment.orderId(), "126.00"));
        assertThat(again.result()).isEqualTo(Result.ALREADY_BOOKED);
        assertThat(again.refund().getId()).isEqualTo(booked.refund().getId());

        assertThat(refundService.refund(request(UUID.randomUUID(), payment.orderId(), "0.01")))
                .isEqualTo(new RefundOutcome(Result.EXCEEDS_REFUNDABLE, null));
        assertThat(refundService.refund(request(UUID.randomUUID(), 424_242L, "1.00")))
                .isEqualTo(new RefundOutcome(Result.NO_CAPTURED_PAYMENT, null));
    }

    @Test
    void aZeroAmountBooksNothing() {
        PaymentDto payment = paid(VISA);

        assertThat(refundService.refund(request(UUID.randomUUID(), payment.orderId(), "0.00")).result())
                .isEqualTo(Result.EXCEEDS_REFUNDABLE);
        assertThat(refundService.refund(request(UUID.randomUUID(), payment.orderId(), "0.004")).result())
                .isEqualTo(Result.EXCEEDS_REFUNDABLE);
    }

    private static CardRefundRequest request(UUID requestId, long orderId, String amount) {
        return new CardRefundRequest(requestId, orderId, new BigDecimal(amount), "test");
    }
}
