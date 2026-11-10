package com.awesome.payment.endpoints;

import com.awesome.payment.client.OrderStatus;
import com.awesome.payment.dto.payment.PaymentDto;
import com.awesome.payment.entity.CardBrand;
import com.awesome.payment.entity.PaymentStatus;
import com.awesome.payment.support.AbstractFlowTest;
import com.awesome.payment.support.FakeBackend;
import com.awesome.payment.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CreatePaymentFlowTest extends AbstractFlowTest {

    @Test
    void visaIsCapturedAndTheBackendMarksTheOrderPaid() {
        long orderId = backend.order("client", "126.00").id();

        Response response = pay(orderId, VISA, clientToken);

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.header("Idempotent-Replayed")).isNull();
        PaymentDto payment = response.as(PaymentDto.class);
        assertThat(payment.id()).isNotNull();
        assertThat(payment.orderId()).isEqualTo(orderId);
        assertThat(payment.status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(payment.amount()).isEqualByComparingTo("126.00");
        assertThat(payment.amount().scale()).isEqualTo(2);
        assertThat(payment.currency()).isEqualTo("USD");
        assertThat(payment.cardBrand()).isEqualTo(CardBrand.VISA);
        assertThat(payment.cardLast4()).isEqualTo("4242");
        assertThat(payment.failureCode()).isNull();
        assertThat(payment.failureMessage()).isNull();
        assertThat(payment.nextAction()).isNull();
        assertThat(payment.refundedAmount()).isEqualByComparingTo("0.00");
        assertThat(payment.createdAt()).isEqualTo(payment.updatedAt()).isNotNull();
        assertThat(response.body()).contains("\"nextAction\":null", "\"failureCode\":null");

        assertThat(backend.order(orderId).status()).isEqualTo(OrderStatus.PAID);
        FakeBackend.Delivery delivery = backend.deliveries().getFirst();
        assertThat(delivery.signatureValid()).isTrue();
        assertThat(delivery.orderId()).isEqualTo(orderId);
        assertThat(delivery.body().get("paymentId").asString()).isEqualTo(payment.id().toString());
        assertThat(delivery.body().get("status").asString()).isEqualTo("CAPTURED");
        assertThat(delivery.body().get("amount").decimalValue()).isEqualByComparingTo("126.00");
        assertThat(delivery.body().get("currency").asString()).isEqualTo("USD");
        assertThat(delivery.body().get("failureCode").isNull()).isTrue();
        assertThat(outboxRepository.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getSentAt()).isNotNull();
            assertThat(row.getAttempts()).isZero();
        });
    }

    @Test
    void mastercardIsCaptured() {
        PaymentDto payment = paid(MASTERCARD);

        assertThat(payment.status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(payment.cardBrand()).isEqualTo(CardBrand.MASTERCARD);
        assertThat(payment.cardLast4()).isEqualTo("4444");
    }

    @ParameterizedTest
    @CsvSource({
            "4000 0000 0000 0002, CARD_DECLINED, Your card was declined",
            "4000 0000 0000 9995, INSUFFICIENT_FUNDS, Your card has insufficient funds"})
    void declinedCardsFailAndTheOrderStaysPending(String number, String code, String message) {
        long orderId = backend.order("client", "50.00").id();

        Response response = pay(orderId, number, clientToken);

        assertThat(response.status()).isEqualTo(201);
        PaymentDto payment = response.as(PaymentDto.class);
        assertThat(payment.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.failureCode()).isEqualTo(code);
        assertThat(payment.failureMessage()).isEqualTo(message);
        assertThat(backend.order(orderId).status()).isEqualTo(OrderStatus.PENDING);
        assertThat(backend.deliveries()).singleElement().satisfies(delivery -> {
            assertThat(delivery.body().get("status").asString()).isEqualTo("FAILED");
            assertThat(delivery.body().get("failureCode").asString()).isEqualTo(code);
        });
    }

    @Test
    void aFailedPaymentDoesNotBlockTheNextAttempt() {
        long orderId = backend.order("client", "50.00").id();
        assertThat(pay(orderId, DECLINED, clientToken).as(PaymentDto.class).status()).isEqualTo(PaymentStatus.FAILED);

        Response retry = pay(orderId, VISA, clientToken);

        assertThat(retry.status()).isEqualTo(201);
        assertThat(retry.as(PaymentDto.class).status()).isEqualTo(PaymentStatus.CAPTURED);
    }

    @Test
    void threeDsCardWaitsForTheChallenge() {
        long orderId = backend.order("client", "10.00").id();

        PaymentDto payment = pay(orderId, THREE_DS, clientToken).as(PaymentDto.class);

        assertThat(payment.status()).isEqualTo(PaymentStatus.REQUIRES_ACTION);
        assertThat(payment.nextAction().type()).isEqualTo("THREE_DS_CHALLENGE");
        assertThat(payment.nextAction().hint()).contains("123456");
        assertThat(backend.deliveries()).isEmpty();
    }

    @Test
    void anyOtherLuhnValidNumberIsCapturedWithItsBrand() {
        assertThat(paid("3782 822463 10005")).satisfies(p -> {
            assertThat(p.status()).isEqualTo(PaymentStatus.CAPTURED);
            assertThat(p.cardBrand()).isEqualTo(CardBrand.AMEX);
            assertThat(p.cardLast4()).isEqualTo("0005");
        });
        assertThat(paid("6011-1111-1111-1117").cardBrand()).isEqualTo(CardBrand.OTHER);
        assertThat(paid("2223003122003222").cardBrand()).isEqualTo(CardBrand.MASTERCARD);
    }

    @Test
    void theAmountDueIsChargedWhenTheBackendSendsIt() {
        long orderId = backend.order("client", OrderStatus.PENDING, "100.00", "60.5").id();

        PaymentDto payment = pay(orderId, VISA, clientToken).as(PaymentDto.class);

        assertThat(payment.amount()).isEqualByComparingTo("60.50");
        assertThat(payment.amount().scale()).isEqualTo(2);
        assertThat(backend.order(orderId).status()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void adminMayPayAnyOrderAndThePaymentBelongsToTheOwner() {
        long orderId = backend.order("client", "20.00").id();

        PaymentDto payment = pay(orderId, VISA, adminToken).as(PaymentDto.class);

        assertThat(payment.status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(getJson(PAYMENTS + "/" + payment.id(), clientToken).status()).isEqualTo(200);
    }

    @Test
    void anotherUsersOrderIsForbidden() {
        long orderId = backend.order("client", "20.00").id();

        Response response = pay(orderId, VISA, otherClientToken);

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.message()).isEqualTo("You can only pay for your own orders");
        assertThat(paymentRepository.count()).isZero();
    }

    @Test
    void unknownOrderIs404() {
        Response response = pay(999_999L, VISA, clientToken);

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.message()).isEqualTo("Order not found");
    }

    @Test
    void ordersThatAreNotPendingAreNotPayable() {
        for (OrderStatus status : new OrderStatus[] {OrderStatus.PAID, OrderStatus.CANCELLED, OrderStatus.SHIPPED,
                OrderStatus.DELIVERED}) {
            long orderId = backend.order("client", status, "20.00", null).id();

            Response response = pay(orderId, VISA, clientToken);

            assertThat(response.status()).as(status.name()).isEqualTo(409);
            assertThat(response.message()).isEqualTo("Order is not payable");
        }
    }

    @Test
    void zeroAmountDueIsNotPayable() {
        long orderId = backend.order("client", OrderStatus.PENDING, "20.00", "0.00").id();

        Response response = pay(orderId, VISA, clientToken);

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.message()).isEqualTo("Order is not payable");
    }

    @Test
    void aPaymentInProgressOrCapturedBlocksANewOne() {
        long pendingOrder = backend.order("client", "20.00").id();
        assertThat(pay(pendingOrder, THREE_DS, clientToken).status()).isEqualTo(201);
        long processingOrder = backend.order("client", "20.00").id();
        assertThat(pay(processingOrder, SETTLES, clientToken).status()).isEqualTo(201);

        assertThat(pay(pendingOrder, VISA, clientToken).status()).isEqualTo(409);
        assertThat(pay(processingOrder, VISA, clientToken).status()).isEqualTo(409);
        assertThat(backend.order(pendingOrder).status()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void aCapturedPaymentBlocksASecondOneEvenIfTheBackendStillSaysPending() {
        long orderId = backend.order("client", "20.00").id();
        backend.failNextResults(503);
        assertThat(pay(orderId, VISA, clientToken).status()).isEqualTo(201);
        assertThat(backend.order(orderId).status()).isEqualTo(OrderStatus.PENDING);

        Response second = pay(orderId, MASTERCARD, clientToken);

        assertThat(second.status()).isEqualTo(409);
        assertThat(second.message()).isEqualTo("Order is not payable");
    }

    @Test
    void luhnInvalidNumberIs400() {
        long orderId = backend.order("client", "20.00").id();

        Response response = pay(orderId, "4242 4242 4242 4241", clientToken);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.message()).isEqualTo("Card number is invalid");
        assertThat(paymentRepository.count()).isZero();
    }

    @Test
    void expiredCardIs400ButTheCurrentMonthIsStillValid() {
        long orderId = backend.order("client", "20.00").id();
        Map<String, Object> expired = new HashMap<>(card(VISA));
        expired.put("expiryMonth", 10);
        expired.put("expiryYear", 2026);

        Response response = pay(newKey(), Map.of("orderId", orderId, "card", expired), clientToken);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.message()).isEqualTo("Card has expired");

        Map<String, Object> thisMonth = new HashMap<>(card(VISA));
        thisMonth.put("expiryMonth", 11);
        thisMonth.put("expiryYear", 2026);
        assertThat(pay(newKey(), Map.of("orderId", orderId, "card", thisMonth), clientToken).status()).isEqualTo(201);
    }

    @Test
    void bodyValidationErrorsAreReportedPerField() {
        Map<String, Object> badCard = new HashMap<>();
        badCard.put("number", "abc");
        badCard.put("expiryMonth", 13);
        badCard.put("expiryYear", 26);
        badCard.put("cvc", "12");
        badCard.put("holderName", " ");

        Response response = pay(newKey(), Map.of("orderId", -1, "card", badCard), clientToken);

        assertThat(response.status()).isEqualTo(400);
        Map<String, String> errors = response.as(new tools.jackson.core.type.TypeReference<>() { });
        assertThat(errors).containsEntry("orderId", "Order id must be positive")
                .containsEntry("card.number", "Card number must contain 12-19 digits")
                .containsEntry("card.expiryMonth", "Expiry month must be between 1 and 12")
                .containsEntry("card.expiryYear", "Expiry year must be a four-digit year")
                .containsEntry("card.cvc", "CVC must be 3 or 4 digits")
                .containsEntry("card.holderName", "Holder name is required");
        assertThat(response.body()).doesNotContain("abc");
    }

    @Test
    void missingCardAndOrderAreRequired() {
        Response response = pay(newKey(), Map.of(), clientToken);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body()).contains("Order id is required", "Card is required");
    }

    @Test
    void malformedJsonIs400WithoutEchoingTheBody() {
        Response response = pay(newKey(), "{\"orderId\": 1, \"card\": {\"number\": 4242424242424242", clientToken);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.message()).isEqualTo("Malformed JSON request");
    }

    @Test
    void idempotencyKeyIsRequiredAndValidated() {
        long orderId = backend.order("client", "20.00").id();
        for (String key : new String[] {null, "short", "has spaces in it", "x".repeat(65), "bad/char-key"}) {
            Response response = pay(key, payment(orderId, VISA), clientToken);

            assertThat(response.status()).as(String.valueOf(key)).isEqualTo(400);
            assertThat(response.message()).startsWith("Idempotency-Key header is required");
        }
        assertThat(pay("a".repeat(64), payment(orderId, VISA), clientToken).status()).isEqualTo(201);
        assertThat(pay("Ab_-1234", payment(backend.order("client", "1.00").id(), VISA), clientToken).status())
                .isEqualTo(201);
    }

    @Test
    void missingOrInvalidTokenIs401() {
        long orderId = backend.order("client", "20.00").id();

        assertThat(pay(orderId, VISA, null).status()).isEqualTo(401);
        Response invalid = pay(orderId, VISA, clientToken + "x");
        assertThat(invalid.status()).isEqualTo(401);
        assertThat(invalid.message()).isEqualTo("Invalid or expired token");
    }

    @Test
    void rolesOtherThanClientOrAdminAreForbidden() {
        long orderId = backend.order("warehouse", "20.00").id();

        Response response = pay(orderId, VISA, TestTokens.token("warehouse", "ROLE_WAREHOUSE"));

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.message()).isEqualTo("Access denied");
    }

    @Test
    void unreachableBackendIs503AndNothingIsStored() {
        long orderId = backend.order("client", "20.00").id();
        // The JWKS is cached by now (earlier requests), so only the order lookup fails.
        pay(backend.order("client", "1.00").id(), VISA, clientToken);
        backend.setAvailable(false);

        Response response = pay(orderId, VISA, clientToken);

        assertThat(response.status()).isEqualTo(503);
        assertThat(response.message()).isEqualTo("Backend unavailable");
        assertThat(paymentRepository.findByOrderIdOrderByCreatedAtDescIdDesc(orderId)).isEmpty();
    }
}
