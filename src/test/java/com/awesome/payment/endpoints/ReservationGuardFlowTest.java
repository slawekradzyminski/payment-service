package com.awesome.payment.endpoints;

import com.awesome.payment.client.OrderStatus;
import com.awesome.payment.dto.payment.PaymentDto;
import com.awesome.payment.entity.PaymentOutboxEntity;
import com.awesome.payment.entity.PaymentStatus;
import com.awesome.payment.support.AbstractFlowTest;
import com.awesome.payment.support.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stock reservations with a time limit (lab contract section 1): the backend's order snapshot
 * carries {@code reservationExpiresAt}; a payment for an order past it is refused with 409, and a
 * capture that reaches the backend after the order expired is voided.
 */
class ReservationGuardFlowTest extends AbstractFlowTest {

    private static final Instant NOW = MutableClock.START;

    @Test
    void anOrderWhoseReservationExpiredIsNotPaid() {
        long orderId = backend.reservedOrder("client", "40.00", NOW.minusMillis(1)).id();

        Response response = pay(orderId, VISA, clientToken);

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.message()).isEqualTo("Order reservation expired");
        assertThat(paymentRepository.count()).isZero();
        assertThat(outboxRepository.count()).isZero();
        assertThat(backend.deliveries()).isEmpty();
        assertThat(backend.order(orderId).status()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void theReservationExpiresAtItsInstantLikeOnTheBackend() {
        long orderId = backend.reservedOrder("client", "40.00", NOW).id();

        Response response = pay(orderId, VISA, clientToken);

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.message()).isEqualTo("Order reservation expired");
    }

    @Test
    void anOrderStillReservedIsPaid() {
        long orderId = backend.reservedOrder("client", "40.00", NOW.plusMillis(1)).id();

        Response response = pay(orderId, VISA, clientToken);

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.as(PaymentDto.class).status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(backend.order(orderId).status()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void anOrderWithoutAReservationLimitNeverExpires() {
        // Older backends (and orders created with reservations disabled) send no reservationExpiresAt.
        long orderId = backend.order("client", "40.00").id();
        clock.advance(Duration.ofDays(365));

        assertThat(pay(orderId, VISA, clientToken).status()).isEqualTo(201);
    }

    @Test
    void anOrderThatIsNoLongerPendingKeepsTheNotPayableAnswer() {
        long orderId = backend.reservedOrder("client", "40.00", NOW.minusSeconds(60)).id();
        backend.expireReservation(orderId);

        Response response = pay(orderId, VISA, clientToken);

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.message()).isEqualTo("Order is not payable");
    }

    @Test
    void anotherUsersExpiredOrderIsStillForbidden() {
        long orderId = backend.reservedOrder("other", "40.00", NOW.minusSeconds(60)).id();

        assertThat(pay(orderId, VISA, clientToken).status()).isEqualTo(403);
    }

    @Test
    void aReplayOfAPaymentMadeBeforeTheExpiryReturnsThatPayment() {
        long orderId = backend.reservedOrder("client", "40.00", NOW.plusSeconds(60)).id();
        String key = newKey();
        PaymentDto paid = pay(key, payment(orderId, VISA), clientToken).as(PaymentDto.class);
        clock.advance(Duration.ofMinutes(5));

        Response replay = pay(key, payment(orderId, VISA), clientToken);

        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.as(PaymentDto.class).id()).isEqualTo(paid.id());
    }

    @Test
    void aCaptureTheBackendRejectsAfterTheReservationExpiredVoidsThePayment() {
        // Paid while reserved with a card that settles later; the reservation expires (and the
        // backend cancels the order) before the provider captures: the backend answers the capture
        // with 409 and the payment is voided (lab contract section 1, "Race").
        long orderId = backend.reservedOrder("client", "40.00", NOW.plusSeconds(5)).id();
        PaymentDto processing = pay(orderId, SETTLES, clientToken).as(PaymentDto.class);
        assertThat(processing.status()).isEqualTo(PaymentStatus.PROCESSING);
        clock.advance(Duration.ofSeconds(5));
        backend.expireReservation(orderId);
        clock.advance(Duration.ofSeconds(5));

        assertThat(settle()).isEqualTo(1);

        PaymentDto voided = getJson(PAYMENTS + "/" + processing.id(), clientToken).as(PaymentDto.class);
        assertThat(voided.status()).isEqualTo(PaymentStatus.VOIDED);
        assertThat(voided.failureCode()).isEqualTo("ORDER_NOT_PAYABLE");
        assertThat(voided.refundedAmount()).isEqualByComparingTo("40.00");
        assertThat(outboxRepository.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getSentAt()).isNotNull();
            assertThat(row.getLastError()).isEqualTo("409 Order is not payable");
        });
        assertThat(backend.order(orderId).status()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void aThreeDsCaptureAfterTheReservationExpiredIsVoidedToo() {
        long orderId = backend.reservedOrder("client", "40.00", NOW.plusSeconds(30)).id();
        PaymentDto challenged = pay(orderId, THREE_DS, clientToken).as(PaymentDto.class);
        clock.advance(Duration.ofSeconds(30));
        backend.expireReservation(orderId);

        threeDs(challenged.id(), "123456", clientToken);

        assertThat(paymentRepository.findById(challenged.id()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.VOIDED);
        assertThat(outboxRepository.findAll()).extracting(PaymentOutboxEntity::getLastError)
                .containsExactly("409 Order is not payable");
    }
}
