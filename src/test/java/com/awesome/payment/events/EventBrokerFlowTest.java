package com.awesome.payment.events;

import com.awesome.payment.client.FeedEvent;
import com.awesome.payment.dto.payment.PaymentDto;
import com.awesome.payment.entity.PaymentStatus;
import com.awesome.payment.repository.RefundRepository;
import com.awesome.payment.support.AbstractFlowTest;
import com.awesome.payment.support.EmbeddedEventBroker;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Card refunds through the broker transport against an embedded Artemis broker with the commerce event
 * topology (docs/EVENT_BROKER_CONTRACT.md in awesome-localstack): delivery, duplicate deliveries and a
 * poison message that ends in the dead-letter queue without blocking the queue.
 */
@Import(EmbeddedEventBroker.class)
@TestPropertySource(properties = {
        "payment.events.transport=broker",
        "spring.artemis.mode=embedded",
        "spring.artemis.embedded.enabled=true",
        "spring.artemis.embedded.persistent=false"})
class EventBrokerFlowTest extends AbstractFlowTest {

    private static final Duration DEADLINE = Duration.ofSeconds(15);

    @Autowired
    private ConnectionFactory connectionFactory;

    @Autowired
    private DefaultMessageListenerContainer eventBrokerListenerContainer;

    @Autowired
    private RefundRepository refundRepository;

    private JmsTemplate sender;

    @BeforeEach
    void sender() {
        sender = new JmsTemplate(connectionFactory);
        sender.setPubSubDomain(true);
        sender.setReceiveTimeout(500);
    }

    @Test
    void theListenerRunsOnThisServicesQueueInsteadOfThePoller() {
        assertThat(eventBrokerListenerContainer.isRunning()).isTrue();
        assertThat(eventBrokerListenerContainer.getDestinationName())
                .isEqualTo("awesome.commerce.events::awesome.commerce.events.payment-service");
    }

    @Test
    void aRefundRequestedThroughTheBrokerIsAppliedOnceEvenWhenDeliveredTwice() {
        // given
        PaymentDto payment = paid(VISA);
        FeedEvent event = refundRequested(payment.orderId(), "26.00");
        long cursorBefore = poller.cursor();

        // when
        publish(event);
        publish(event);

        // then
        await(() -> processedEvents.existsById(event.id()));
        await(() -> refundRepository.findAll().size() == 1);
        assertThat(refundRepository.findAll()).singleElement()
                .satisfies(refund -> assertThat(refund.getAmount()).isEqualByComparingTo("26.00"));
        assertThat(paymentRepository.findById(payment.id()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(poller.cursor()).as("broker deliveries leave the feed cursor alone").isEqualTo(cursorBefore);
    }

    @Test
    void aPoisonMessageEndsInTheDeadLetterQueueAndTheNextEventIsStillHandled() throws Exception {
        // given
        PaymentDto payment = paid(VISA);
        FeedEvent good = refundRequested(payment.orderId(), "10.00");

        // when
        sender.send(EmbeddedEventBroker.ADDRESS, session -> {
            TextMessage message = session.createTextMessage("{\"not\":\"an event\"");
            message.setStringProperty("type", CardRefundRequestedHandler.EVENT_TYPE);
            return message;
        });
        publish(good);

        // then
        await(() -> processedEvents.existsById(good.id()));
        JmsTemplate receiver = new JmsTemplate(connectionFactory);
        receiver.setReceiveTimeout(DEADLINE.toMillis());
        Message dead = receiver.receive(EmbeddedEventBroker.DEAD_LETTER_QUEUE);
        assertThat(dead).isInstanceOf(TextMessage.class);
        assertThat(((TextMessage) dead).getText()).isEqualTo("{\"not\":\"an event\"");
        assertThat(dead.getStringProperty("_AMQ_ORIG_QUEUE")).isEqualTo(EmbeddedEventBroker.QUEUE);
    }

    @Test
    void theRelaysTraceparentBecomesTheParentOfTheProcessSpan() {
        FeedEvent event = new FeedEvent(1, UUID.randomUUID(), "card-refund.requested", "2026-11-15T10:00:00Z", 1L,
                "client", null, "req-1", "a".repeat(32), "b".repeat(16));

        FeedEvent parented = EventBrokerListener.parentedBy(event, "00-" + "c".repeat(32) + "-" + "d".repeat(16) + "-01");
        FeedEvent unchanged = EventBrokerListener.parentedBy(event, "garbage");

        assertThat(parented.traceId()).isEqualTo("c".repeat(32));
        assertThat(parented.spanId()).isEqualTo("d".repeat(16));
        assertThat(parented.requestId()).isEqualTo("req-1");
        assertThat(unchanged).isEqualTo(event);
    }

    private FeedEvent refundRequested(long orderId, String amount) {
        return new FeedEvent(System.nanoTime(), UUID.randomUUID(), CardRefundRequestedHandler.EVENT_TYPE,
                Instant.now().toString(), orderId, "client",
                jsonMapper.valueToTree(Map.of("requestId", UUID.randomUUID().toString(), "orderId", orderId,
                        "amount", new BigDecimal(amount), "reason", "Return #1")),
                null, null, null);
    }

    private void publish(FeedEvent event) {
        String body = jsonMapper.writeValueAsString(event);
        sender.send(EmbeddedEventBroker.ADDRESS, session -> {
            TextMessage message = session.createTextMessage(body);
            message.setStringProperty("type", event.type());
            message.setStringProperty("JMSXGroupID", "order-" + event.orderId());
            return message;
        });
    }

    private static void await(BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(DEADLINE);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Condition not met within " + DEADLINE);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}
