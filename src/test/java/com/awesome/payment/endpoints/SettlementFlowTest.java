package com.awesome.payment.endpoints;

import com.awesome.payment.client.OrderStatus;
import com.awesome.payment.dto.payment.PaymentDto;
import com.awesome.payment.entity.PaymentStatus;
import com.awesome.payment.service.SettlementJob;
import com.awesome.payment.support.AbstractFlowTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SettlementFlowTest extends AbstractFlowTest {

    @Autowired
    private SettlementJob settlementJob;

    @Test
    void processingCardSettlesToCapturedAfterTheDelay() {
        PaymentDto processing = paid(SETTLES);
        assertThat(processing.status()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(processing.nextAction()).isNull();
        assertThat(backend.deliveries()).isEmpty();

        clock.advance(Duration.ofSeconds(9));
        assertThat(settle()).isZero();
        assertThat(get(processing).status()).isEqualTo(PaymentStatus.PROCESSING);

        clock.advance(Duration.ofSeconds(1));
        assertThat(settle()).isEqualTo(1);

        PaymentDto settled = get(processing);
        assertThat(settled.status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(settled.updatedAt()).isAfter(settled.createdAt());
        assertThat(backend.order(processing.orderId()).status()).isEqualTo(OrderStatus.PAID);
        assertThat(backend.deliveries()).singleElement()
                .satisfies(d -> assertThat(d.body().get("status").asString()).isEqualTo("CAPTURED"));
        assertThat(paymentRepository.findById(processing.id()).orElseThrow().getSettleAt()).isNull();
        assertThat(settle()).isZero();
    }

    @Test
    void timeoutCardFailsWithProviderTimeoutAfterTheDelay() {
        PaymentDto processing = paid(TIMES_OUT);
        assertThat(processing.status()).isEqualTo(PaymentStatus.PROCESSING);

        clock.advance(Duration.ofSeconds(10));
        settle();

        PaymentDto failed = get(processing);
        assertThat(failed.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(failed.failureCode()).isEqualTo("PROVIDER_TIMEOUT");
        assertThat(failed.failureMessage()).isEqualTo("The payment provider did not respond in time");
        assertThat(backend.order(processing.orderId()).status()).isEqualTo(OrderStatus.PENDING);
        assertThat(backend.deliveries()).singleElement().satisfies(d -> {
            assertThat(d.body().get("status").asString()).isEqualTo("FAILED");
            assertThat(d.body().get("failureCode").asString()).isEqualTo("PROVIDER_TIMEOUT");
        });
    }

    @Test
    void theScheduledRunIsSwitchedOffInTests() {
        PaymentDto processing = paid(SETTLES);
        clock.advance(Duration.ofMinutes(1));

        settlementJob.scheduledSettle();

        assertThat(get(processing).status()).isEqualTo(PaymentStatus.PROCESSING);
    }

    @Test
    void severalDuePaymentsSettleInOneRun() {
        paid(SETTLES);
        paid(TIMES_OUT);
        clock.advance(Duration.ofSeconds(10));

        assertThat(settle()).isEqualTo(2);
    }

    private PaymentDto get(PaymentDto payment) {
        return getJson(PAYMENTS + "/" + payment.id(), clientToken).as(PaymentDto.class);
    }
}
