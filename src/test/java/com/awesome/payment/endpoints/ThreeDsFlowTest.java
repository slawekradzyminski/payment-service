package com.awesome.payment.endpoints;

import com.awesome.payment.client.OrderStatus;
import com.awesome.payment.dto.payment.PaymentDto;
import com.awesome.payment.entity.PaymentStatus;
import com.awesome.payment.support.AbstractFlowTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ThreeDsFlowTest extends AbstractFlowTest {

    @Test
    void correctCodeCapturesAndNotifiesTheBackend() {
        PaymentDto pending = paid(THREE_DS);
        clock.advance(Duration.ofSeconds(30));

        Response response = threeDs(pending.id(), "123456", clientToken);

        assertThat(response.status()).isEqualTo(200);
        PaymentDto captured = response.as(PaymentDto.class);
        assertThat(captured.status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(captured.nextAction()).isNull();
        assertThat(captured.updatedAt()).isAfter(captured.createdAt());
        assertThat(backend.order(pending.orderId()).status()).isEqualTo(OrderStatus.PAID);
        assertThat(backend.deliveries()).singleElement()
                .satisfies(d -> assertThat(d.body().get("status").asString()).isEqualTo("CAPTURED"));
    }

    @Test
    void wrongCodeFailsThePaymentAndTheOrderCanBePaidAgain() {
        PaymentDto pending = paid(THREE_DS);

        PaymentDto failed = threeDs(pending.id(), "000000", clientToken).as(PaymentDto.class);

        assertThat(failed.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(failed.failureCode()).isEqualTo("THREE_DS_FAILED");
        assertThat(failed.failureMessage()).isEqualTo("3-D Secure authentication failed");
        assertThat(failed.nextAction()).isNull();
        assertThat(backend.order(pending.orderId()).status()).isEqualTo(OrderStatus.PENDING);
        assertThat(backend.deliveries()).singleElement().satisfies(d -> {
            assertThat(d.body().get("status").asString()).isEqualTo("FAILED");
            assertThat(d.body().get("failureCode").asString()).isEqualTo("THREE_DS_FAILED");
        });
        assertThat(pay(pending.orderId(), VISA, clientToken).status()).isEqualTo(201);
    }

    @Test
    void answeringTwiceIs409() {
        PaymentDto pending = paid(THREE_DS);
        threeDs(pending.id(), "123456", clientToken);

        Response again = threeDs(pending.id(), "123456", clientToken);

        assertThat(again.status()).isEqualTo(409);
        assertThat(again.message()).isEqualTo("Payment does not require action");
        assertThat(threeDs(paid(VISA).id(), "123456", clientToken).status()).isEqualTo(409);
    }

    @Test
    void unknownPaymentIs404AndBadIdIs400() {
        Response unknown = threeDs(UUID.randomUUID(), "123456", clientToken);
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(unknown.message()).isEqualTo("Payment not found");

        Response badId = postJson(PAYMENTS + "/not-a-uuid/3ds", Map.of("code", "123456"), clientToken);
        assertThat(badId.status()).isEqualTo(400);
        assertThat(badId.message()).isEqualTo("Invalid value for id");
    }

    @Test
    void anotherUsersPaymentIsNotFoundButAdminMayAnswer() {
        PaymentDto pending = paid(THREE_DS);

        Response other = threeDs(pending.id(), "123456", otherClientToken);
        assertThat(other.status()).isEqualTo(404);
        assertThat(other.message()).isEqualTo("Payment not found");
        assertThat(threeDs(pending.id(), "123456", adminToken).status()).isEqualTo(200);
    }

    @Test
    void codeIsRequired() {
        PaymentDto pending = paid(THREE_DS);

        Response response = postJson(PAYMENTS + "/" + pending.id() + "/3ds", Map.of(), clientToken);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body()).contains("Code is required");
        assertThat(threeDs(pending.id(), "123456", null).status()).isEqualTo(401);
    }
}
