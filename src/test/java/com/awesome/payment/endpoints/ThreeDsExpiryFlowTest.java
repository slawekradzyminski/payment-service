package com.awesome.payment.endpoints;

import com.awesome.payment.client.OrderStatus;
import com.awesome.payment.dto.payment.PaymentDto;
import com.awesome.payment.entity.PaymentStatus;
import com.awesome.payment.support.AbstractFlowTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ThreeDsExpiryFlowTest extends AbstractFlowTest {

    @Test
    void theSettlementJobExpiresAnUnansweredChallengeAndNotifiesTheBackend() {
        PaymentDto pending = paid(THREE_DS);

        clock.advance(Duration.ofMinutes(15).minusSeconds(1));
        assertThat(settle()).isZero();
        assertThat(current(pending).status()).isEqualTo(PaymentStatus.REQUIRES_ACTION);

        clock.advance(Duration.ofSeconds(1));
        assertThat(settle()).isEqualTo(1);

        PaymentDto expired = current(pending);
        assertThat(expired.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(expired.failureCode()).isEqualTo("THREE_DS_EXPIRED");
        assertThat(expired.failureMessage()).isEqualTo("3-D Secure was not completed in time");
        assertThat(expired.nextAction()).isNull();
        assertThat(paymentRepository.findById(pending.id()).orElseThrow().getSettleAt()).isNull();
        assertThat(backend.order(pending.orderId()).status()).isEqualTo(OrderStatus.PENDING);
        assertThat(backend.deliveries()).singleElement().satisfies(d -> {
            assertThat(d.signatureValid()).isTrue();
            assertThat(d.body().get("status").asString()).isEqualTo("FAILED");
            assertThat(d.body().get("failureCode").asString()).isEqualTo("THREE_DS_EXPIRED");
        });
    }

    @Test
    void anExpiredChallengeDoesNotBlockANewPaymentEvenBeforeTheJobRuns() {
        PaymentDto pending = paid(THREE_DS);
        assertThat(pay(pending.orderId(), VISA, clientToken).status()).isEqualTo(409);

        clock.advance(Duration.ofMinutes(15));
        Response retry = pay(pending.orderId(), VISA, clientToken);

        assertThat(retry.status()).isEqualTo(201);
        assertThat(retry.as(PaymentDto.class).status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(current(pending).failureCode()).isEqualTo("THREE_DS_EXPIRED");
        assertThat(backend.deliveries()).extracting(d -> d.body().get("status").asString())
                .containsExactly("FAILED", "CAPTURED");
        assertThat(backend.order(pending.orderId()).status()).isEqualTo(OrderStatus.PAID);
        assertThat(settle()).isZero();
    }

    @Test
    void aLateAnswerExpiresTheChallengeAndIs409() {
        PaymentDto pending = paid(THREE_DS);
        clock.advance(Duration.ofMinutes(16));

        Response late = threeDs(pending.id(), "123456", clientToken);

        assertThat(late.status()).isEqualTo(409);
        assertThat(late.message()).isEqualTo("Payment does not require action");
        assertThat(current(pending).failureCode()).isEqualTo("THREE_DS_EXPIRED");
        assertThat(backend.deliveries()).singleElement()
                .satisfies(d -> assertThat(d.body().get("failureCode").asString()).isEqualTo("THREE_DS_EXPIRED"));
    }

    @Test
    void anAnswerJustBeforeTheDeadlineStillCounts() {
        PaymentDto pending = paid(THREE_DS);
        clock.advance(Duration.ofMinutes(15).minusSeconds(1));

        assertThat(threeDs(pending.id(), "123456", clientToken).as(PaymentDto.class).status())
                .isEqualTo(PaymentStatus.CAPTURED);
    }

    private PaymentDto current(PaymentDto payment) {
        return getJson(PAYMENTS + "/" + payment.id(), clientToken).as(PaymentDto.class);
    }
}
