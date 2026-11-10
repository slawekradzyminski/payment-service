package com.awesome.payment.endpoints;

import com.awesome.payment.dto.payment.PaymentDto;
import com.awesome.payment.entity.PaymentStatus;
import com.awesome.payment.support.AbstractFlowTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyFlowTest extends AbstractFlowTest {

    @Test
    void sameKeyAndBodyReplaysTheOriginalPaymentWith200() {
        long orderId = backend.order("client", "30.00").id();
        String key = newKey();
        PaymentDto first = pay(key, payment(orderId, VISA), clientToken).as(PaymentDto.class);

        Response replay = pay(key, payment(orderId, VISA), clientToken);

        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.as(PaymentDto.class)).isEqualTo(first);
        assertThat(paymentRepository.count()).isEqualTo(1);
        assertThat(backend.deliveries()).hasSize(1);
    }

    @Test
    void replayIsRecognisedEvenWithDifferentlyFormattedCardNumber() {
        long orderId = backend.order("client", "30.00").id();
        String key = newKey();
        pay(key, payment(orderId, VISA), clientToken);

        assertThat(pay(key, payment(orderId, "4242424242424242"), clientToken).status()).isEqualTo(200);
    }

    @Test
    void replayShowsTheCurrentStateOfThePayment() {
        long orderId = backend.order("client", "30.00").id();
        String key = newKey();
        PaymentDto created = pay(key, payment(orderId, THREE_DS), clientToken).as(PaymentDto.class);
        clock.advance(Duration.ofSeconds(5));
        threeDs(created.id(), "123456", clientToken);

        PaymentDto replayed = pay(key, payment(orderId, THREE_DS), clientToken).as(PaymentDto.class);

        assertThat(replayed.id()).isEqualTo(created.id());
        assertThat(replayed.status()).isEqualTo(PaymentStatus.CAPTURED);
    }

    @Test
    void sameKeyWithADifferentBodyIs409() {
        long orderId = backend.order("client", "30.00").id();
        String key = newKey();
        pay(key, payment(orderId, DECLINED), clientToken);
        Map<String, Object> otherCvc = new HashMap<>(card(DECLINED));
        otherCvc.put("cvc", "123");

        for (Object body : new Object[] {payment(orderId, VISA), Map.of("orderId", orderId, "card", otherCvc),
                payment(backend.order("client", "1.00").id(), DECLINED)}) {
            Response conflict = pay(key, body, clientToken);

            assertThat(conflict.status()).isEqualTo(409);
            assertThat(conflict.message()).isEqualTo("Idempotency-Key was already used with a different request");
        }
        assertThat(paymentRepository.count()).isEqualTo(1);
    }

    @Test
    void keysAreScopedPerCaller() {
        String key = newKey();
        long clientOrder = backend.order("client", "30.00").id();
        long otherOrder = backend.order("other", "30.00").id();

        assertThat(pay(key, payment(clientOrder, VISA), clientToken).status()).isEqualTo(201);
        assertThat(pay(key, payment(otherOrder, VISA), otherClientToken).status()).isEqualTo(201);
    }

    @Test
    void rejectedRequestsDoNotConsumeTheKey() {
        long orderId = backend.order("client", "30.00").id();
        String key = newKey();

        assertThat(pay(key, payment(orderId, "4242 4242 4242 4241"), clientToken).status()).isEqualTo(400);
        assertThat(pay(key, payment(orderId, VISA), clientToken).status()).isEqualTo(201);
    }
}
