package com.awesome.payment.events;

import com.awesome.payment.client.FeedEvent;
import com.awesome.payment.client.OrderStatus;
import com.awesome.payment.dto.payment.PaymentDto;
import com.awesome.payment.entity.PaymentStatus;
import com.awesome.payment.repository.RefundRepository;
import com.awesome.payment.support.AbstractFlowTest;
import com.awesome.payment.support.FakeBackend.FeedRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Card refunds requested through the backend event feed, end to end on H2 with {@code FakeBackend}. */
class EventFeedRefundFlowTest extends AbstractFlowTest {

    private static final String TYPE = CardRefundRequestedHandler.EVENT_TYPE;

    @Autowired
    private RefundRepository refundRepository;

    @Test
    void partialRefundsThenFullRefundThenNothingLeft() {
        PaymentDto payment = paid(VISA);

        refundRequested(UUID.randomUUID(), payment.orderId(), "26.00");
        assertThat(poller.poll()).isEqualTo(1);
        assertThat(current(payment).status()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(current(payment).refundedAmount()).isEqualByComparingTo("26.00");

        refundRequested(UUID.randomUUID(), payment.orderId(), "100");
        FeedEvent nothingLeft = refundRequested(UUID.randomUUID(), payment.orderId(), "0.01");
        assertThat(poller.poll()).isEqualTo(2);

        PaymentDto refunded = current(payment);
        assertThat(refunded.status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(refunded.refundedAmount()).isEqualByComparingTo("126.00");
        assertThat(refundRepository.findAll()).hasSize(2);
        assertThat(processedEvents.existsById(nothingLeft.id())).isTrue();
        assertThat(poller.cursor()).isEqualTo(nothingLeft.sequence());
    }

    @Test
    void theRefundRecordsRequestIdOrderAmountAndReason() {
        PaymentDto payment = paid(VISA);
        UUID requestId = UUID.randomUUID();

        refundRequested(requestId, payment.orderId(), "10.005");
        poller.poll();

        assertThat(refundRepository.findByRequestId(requestId)).hasValueSatisfying(refund -> {
            assertThat(refund.getPaymentId()).isEqualTo(payment.id());
            assertThat(refund.getOrderId()).isEqualTo(payment.orderId());
            assertThat(refund.getAmount()).isEqualByComparingTo("10.01");
            assertThat(refund.getReason()).isEqualTo("Return #1");
            assertThat(refund.getPaymentStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        });
    }

    @Test
    void theSameEventDeliveredTwiceIsHandledOnce() {
        PaymentDto payment = paid(VISA);
        FeedEvent event = refundRequested(UUID.randomUUID(), payment.orderId(), "10.00");
        poller.poll();

        // The cursor is rewound (e.g. restored backup) and the event is delivered again with a new sequence too.
        eventCursors.findById(EventFeedPoller.CONSUMER).ifPresent(cursor -> {
            cursor.setLastSequence(0);
            eventCursors.save(cursor);
        });
        FeedEvent redelivered = backend.republish(event);
        poller.poll();

        assertThat(current(payment).refundedAmount()).isEqualByComparingTo("10.00");
        assertThat(refundRepository.findAll()).hasSize(1);
        assertThat(poller.cursor()).isEqualTo(redelivered.sequence());
    }

    @Test
    void theSameRequestIdInTwoEventsIsRefundedOnce() {
        PaymentDto payment = paid(VISA);
        UUID requestId = UUID.randomUUID();

        FeedEvent first = refundRequested(requestId, payment.orderId(), "10.00");
        FeedEvent second = refundRequested(requestId, payment.orderId(), "10.00");
        assertThat(poller.poll()).isEqualTo(2);

        assertThat(current(payment).refundedAmount()).isEqualByComparingTo("10.00");
        assertThat(refundRepository.findAll()).hasSize(1);
        assertThat(processedEvents.existsById(first.id())).isTrue();
        assertThat(processedEvents.existsById(second.id())).isTrue();
    }

    @Test
    void ordersWithoutACapturedPaymentBookNothingAndAreSkipped() {
        PaymentDto failed = paid(DECLINED);
        PaymentDto pending = paid(THREE_DS);
        PaymentDto captured = paid(VISA);

        refundRequested(UUID.randomUUID(), failed.orderId(), "1.00");
        refundRequested(UUID.randomUUID(), pending.orderId(), "1.00");
        refundRequested(UUID.randomUUID(), 424_242L, "1.00");
        FeedEvent last = refundRequested(UUID.randomUUID(), captured.orderId(), "1.00");

        assertThat(poller.poll()).isEqualTo(4);

        assertThat(refundRepository.findAll()).singleElement()
                .satisfies(refund -> assertThat(refund.getOrderId()).isEqualTo(captured.orderId()));
        assertThat(current(failed).status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(current(pending).status()).isEqualTo(PaymentStatus.REQUIRES_ACTION);
        assertThat(poller.cursor()).isEqualTo(last.sequence());
    }

    @Test
    void aVoidedPaymentHasNothingToRefund() {
        long orderId = backend.order("client", "40.00").id();
        PaymentDto pending = pay(orderId, THREE_DS, clientToken).as(PaymentDto.class);
        backend.setStatus(orderId, OrderStatus.CANCELLED);
        threeDs(pending.id(), "123456", clientToken);
        assertThat(current(pending).status()).isEqualTo(PaymentStatus.VOIDED);

        refundRequested(UUID.randomUUID(), orderId, "1.00");
        poller.poll();

        assertThat(refundRepository.findAll()).isEmpty();
        assertThat(current(pending).refundedAmount()).isEqualByComparingTo("40.00");
    }

    @Test
    void moreThanTheRefundableRestBooksNothingButTheFeedMovesOn() {
        PaymentDto payment = paid(VISA);
        refundRequested(UUID.randomUUID(), payment.orderId(), "100.00");
        FeedEvent tooMuch = refundRequested(UUID.randomUUID(), payment.orderId(), "26.01");
        FeedEvent fits = refundRequested(UUID.randomUUID(), payment.orderId(), "26.00");

        assertThat(poller.poll()).isEqualTo(3);

        assertThat(processedEvents.existsById(tooMuch.id())).isTrue();
        assertThat(current(payment).status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(current(payment).refundedAmount()).isEqualByComparingTo("126.00");
        assertThat(poller.cursor()).isEqualTo(fits.sequence());
    }

    @Test
    void theBackendBeingDownLeavesTheCursorWhereItWas() {
        PaymentDto payment = paid(VISA);
        refundRequested(UUID.randomUUID(), payment.orderId(), "5.00");
        backend.setAvailable(false);

        assertThat(poller.poll()).isZero();

        assertThat(poller.cursor()).isZero();
        assertThat(current(payment).refundedAmount()).isEqualByComparingTo("0.00");
        backend.setAvailable(true);
        assertThat(poller.poll()).isEqualTo(1);
        assertThat(current(payment).refundedAmount()).isEqualByComparingTo("5.00");
    }

    @Test
    void aMalformedEventIsLoggedSkippedAndDoesNotBlockTheFeed() {
        PaymentDto payment = paid(VISA);
        Map<String, Object> noRequestId = new HashMap<>(data(UUID.randomUUID(), payment.orderId(), "5.00"));
        noRequestId.remove("requestId");
        FeedEvent broken = backend.publish(TYPE, payment.orderId(), noRequestId);
        FeedEvent negative = backend.publish(TYPE, payment.orderId(), data(UUID.randomUUID(), payment.orderId(), "-1"));
        FeedEvent good = refundRequested(UUID.randomUUID(), payment.orderId(), "5.00");

        assertThat(poller.poll()).isEqualTo(3);

        assertThat(processedEvents.existsById(broken.id())).isTrue();
        assertThat(processedEvents.existsById(negative.id())).isTrue();
        assertThat(current(payment).refundedAmount()).isEqualByComparingTo("5.00");
        assertThat(poller.cursor()).isEqualTo(good.sequence());
        assertThat(poller.poll()).isZero();
    }

    @Test
    void onlyCardRefundEventsAreRequestedAndTheCursorSkipsOtherTypes() {
        PaymentDto payment = paid(VISA);
        refundRequested(UUID.randomUUID(), payment.orderId(), "5.00");
        FeedEvent other = backend.publish("order.cancelled", payment.orderId(), Map.of("orderId", payment.orderId()));

        assertThat(poller.poll()).isEqualTo(1);

        assertThat(backend.feedRequests()).first()
                .isEqualTo(new FeedRequest(0, EventFeedPoller.PAGE_SIZE, List.of(TYPE)));
        assertThat(processedEvents.existsById(other.id())).isFalse();
        assertThat(poller.cursor()).isEqualTo(other.sequence());
    }

    @Test
    void aBacklogIsReadPageByPageAndTheCursorIsPersisted() {
        PaymentDto payment = paid(VISA);
        int events = EventFeedPoller.PAGE_SIZE * 2 + 5;
        FeedEvent last = null;
        for (int i = 0; i < events; i++) {
            last = refundRequested(UUID.randomUUID(), payment.orderId(), "0.10");
        }

        long lastSequence = last.sequence();
        assertThat(poller.poll()).isEqualTo(events);

        assertThat(backend.feedRequests()).extracting(FeedRequest::after)
                .containsExactly(0L, (long) EventFeedPoller.PAGE_SIZE, 2L * EventFeedPoller.PAGE_SIZE);
        assertThat(eventCursors.findById(EventFeedPoller.CONSUMER)).hasValueSatisfying(
                cursor -> assertThat(cursor.getLastSequence()).isEqualTo(lastSequence));
        assertThat(current(payment).refundedAmount()).isEqualByComparingTo(new BigDecimal("20.50"));
        assertThat(poller.poll()).isZero();
        assertThat(backend.feedRequests()).last().extracting(FeedRequest::after).isEqualTo(lastSequence);
    }

    @Test
    void theInboundRefundEndpointIsGone() {
        PaymentDto payment = paid(VISA);
        Map<String, Object> body = data(UUID.randomUUID(), payment.orderId(), "1.00");

        assertThat(postJson("/internal/v1/payments/refunds", body, null).status()).isEqualTo(401);
        assertThat(postJson("/internal/v1/payments/refunds", body, adminToken).status()).isEqualTo(404);
        assertThat(getJson("/v3/api-docs", null).body()).doesNotContain("/internal/");
        assertThat(current(payment).refundedAmount()).isEqualByComparingTo("0.00");
    }

    private FeedEvent refundRequested(UUID requestId, long orderId, String amount) {
        return backend.publish(TYPE, orderId, data(requestId, orderId, amount));
    }

    private static Map<String, Object> data(UUID requestId, long orderId, String amount) {
        return Map.of("requestId", requestId.toString(), "orderId", orderId, "amount", new BigDecimal(amount),
                "reason", "Return #1");
    }

    private PaymentDto current(PaymentDto payment) {
        return getJson(PAYMENTS + "/" + payment.id(), adminToken).as(PaymentDto.class);
    }
}
