package com.awesome.payment.endpoints;

import com.awesome.payment.client.OrderStatus;
import com.awesome.payment.dto.payment.PaymentDto;
import com.awesome.payment.entity.PaymentStatus;
import com.awesome.payment.support.AbstractFlowTest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RefundFlowTest extends AbstractFlowTest {

    @Test
    void partialRefundsThenFullRefund() {
        PaymentDto payment = paid(VISA);

        Response first = refund(body(UUID.randomUUID(), payment.orderId(), "26.00"), SERVICE_TOKEN);

        assertThat(first.status()).isEqualTo(200);
        JsonNode firstBody = jsonMapper.readTree(first.body());
        assertThat(firstBody.get("refundId").asString()).isNotBlank();
        assertThat(firstBody.get("paymentId").asString()).isEqualTo(payment.id().toString());
        assertThat(firstBody.get("amount").decimalValue()).isEqualByComparingTo("26.00");
        assertThat(firstBody.get("status").asString()).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(current(payment).refundedAmount()).isEqualByComparingTo("26.00");

        Response second = refund(body(UUID.randomUUID(), payment.orderId(), "100"), SERVICE_TOKEN);

        assertThat(second.status()).isEqualTo(200);
        assertThat(jsonMapper.readTree(second.body()).get("status").asString()).isEqualTo("REFUNDED");
        PaymentDto refunded = current(payment);
        assertThat(refunded.status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(refunded.refundedAmount()).isEqualByComparingTo("126.00");

        Response nothingLeft = refund(body(UUID.randomUUID(), payment.orderId(), "0.01"), SERVICE_TOKEN);
        assertThat(nothingLeft.status()).isEqualTo(409);
        assertThat(nothingLeft.message()).isEqualTo("Refund exceeds the refundable amount");
    }

    @Test
    void theSameRequestIdIsBookedOnce() {
        PaymentDto payment = paid(VISA);
        UUID requestId = UUID.randomUUID();

        Response first = refund(body(requestId, payment.orderId(), "10.00"), SERVICE_TOKEN);
        Response replay = refund(body(requestId, payment.orderId(), "10.00"), SERVICE_TOKEN);

        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(current(payment).refundedAmount()).isEqualByComparingTo("10.00");
    }

    @Test
    void moreThanTheRefundableRestIs409() {
        PaymentDto payment = paid(VISA);
        refund(body(UUID.randomUUID(), payment.orderId(), "100.00"), SERVICE_TOKEN);

        Response response = refund(body(UUID.randomUUID(), payment.orderId(), "26.01"), SERVICE_TOKEN);

        assertThat(response.status()).isEqualTo(409);
        assertThat(current(payment).refundedAmount()).isEqualByComparingTo("100.00");
        assertThat(current(payment).status()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
    }

    @Test
    void ordersWithoutACapturedPaymentAre404() {
        PaymentDto failed = paid(DECLINED);
        PaymentDto pending = paid(THREE_DS);

        for (long orderId : new long[] {failed.orderId(), pending.orderId(), 424_242L}) {
            Response response = refund(body(UUID.randomUUID(), orderId, "1.00"), SERVICE_TOKEN);

            assertThat(response.status()).isEqualTo(404);
            assertThat(response.message()).isEqualTo("No captured payment for this order");
        }
    }

    @Test
    void aVoidedPaymentHasNothingToRefund() {
        long orderId = backend.order("client", "40.00").id();
        backend.setStatus(orderId, OrderStatus.PENDING);
        PaymentDto pending = pay(orderId, THREE_DS, clientToken).as(PaymentDto.class);
        backend.setStatus(orderId, OrderStatus.CANCELLED);
        threeDs(pending.id(), "123456", clientToken);

        assertThat(refund(body(UUID.randomUUID(), orderId, "1.00"), SERVICE_TOKEN).status()).isEqualTo(404);
    }

    @Test
    void invalidBodiesAre400() {
        Map<String, Object> missing = new HashMap<>();
        missing.put("reason", "x".repeat(256));

        Response response = refund(missing, SERVICE_TOKEN);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body()).contains("Request id is required", "Order id is required", "Amount is required",
                "Reason must be at most 255 characters");
        assertThat(refund(body(UUID.randomUUID(), 1L, "0.00"), SERVICE_TOKEN).body())
                .contains("Amount must be at least 0.01");
        assertThat(refund(body(UUID.randomUUID(), 1L, "1.001"), SERVICE_TOKEN).body())
                .contains("Amount must have at most two decimal places");
    }

    @Test
    void theServiceTokenIsRequired() {
        PaymentDto payment = paid(VISA);
        Map<String, Object> body = body(UUID.randomUUID(), payment.orderId(), "1.00");

        Response missing = refund(body, null);
        assertThat(missing.status()).isEqualTo(401);
        assertThat(missing.message()).isEqualTo("Invalid service token");
        assertThat(refund(body, "wrong-token").status()).isEqualTo(401);
        assertThat(refund(body, SERVICE_TOKEN + "x").status()).isEqualTo(401);
        // A user JWT does not open the internal API.
        assertThat(postJson(REFUNDS, body, adminToken).status()).isEqualTo(401);
        assertThat(current(payment).refundedAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    void theInternalApiIsNotInTheOpenApiDocument() {
        assertThat(getJson("/v3/api-docs", null).body()).doesNotContain("/internal/");
    }

    private static Map<String, Object> body(UUID requestId, long orderId, String amount) {
        return Map.of("requestId", requestId, "orderId", orderId, "amount", new java.math.BigDecimal(amount),
                "reason", "Return #1");
    }

    private PaymentDto current(PaymentDto payment) {
        return getJson(PAYMENTS + "/" + payment.id(), adminToken).as(PaymentDto.class);
    }
}
