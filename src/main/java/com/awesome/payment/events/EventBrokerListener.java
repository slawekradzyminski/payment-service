package com.awesome.payment.events;

import com.awesome.payment.client.FeedEvent;
import com.awesome.payment.correlation.Correlation;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageListener;
import jakarta.jms.TextMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jms.support.JmsUtils;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Broker half of the event transport (docs/EVENT_BROKER_CONTRACT.md in awesome-localstack): the body
 * of every message on this service's queue is one feed event, handled by the same code as the poller
 * ({@link EventFeedPoller#processDelivered}). Runs in the listener container's transacted session:
 * whatever fails here (a body that does not parse, a handler or database failure) rolls the message
 * back, the broker redelivers it with a growing delay and, after its last attempt, moves it to the
 * queue's dead-letter queue. The {@code traceparent} property (the backend relay's publish span), when
 * valid, becomes the parent of the {@code process <type>} span instead of the writing request's span.
 */
@Slf4j
@Component
public class EventBrokerListener implements MessageListener {

    static final String TRACEPARENT = "traceparent";

    private final EventFeedPoller poller;
    private final JsonMapper jsonMapper;

    public EventBrokerListener(EventFeedPoller poller, JsonMapper jsonMapper) {
        this.poller = poller;
        this.jsonMapper = jsonMapper;
    }

    /** {@code <address>::<address>.<consumer>}: this service's queue by its fully qualified name. */
    public static String queueOf(String address) {
        return address + "::" + address + "." + EventFeedPoller.CONSUMER;
    }

    @Override
    public void onMessage(Message message) {
        try {
            if (!(message instanceof TextMessage text)) {
                throw new IllegalArgumentException("Event message " + message.getJMSMessageID() + " is not a text message");
            }
            FeedEvent event = parentedBy(parse(text), message.getStringProperty(TRACEPARENT));
            poller.processDelivered(event);
        } catch (JMSException e) {
            throw JmsUtils.convertJmsAccessException(e);
        }
    }

    private FeedEvent parse(TextMessage message) throws JMSException {
        String body = message.getText();
        try {
            FeedEvent event = jsonMapper.readValue(body, FeedEvent.class);
            if (event.id() == null || event.type() == null) {
                throw new IllegalArgumentException("Event message " + message.getJMSMessageID() + " has no id or type");
            }
            return event;
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Event message " + message.getJMSMessageID() + " is not a feed event: "
                    + e.getOriginalMessage(), e);
        }
    }

    /** The event with the publish span of a valid {@code traceparent} as its parent; unchanged otherwise. */
    static FeedEvent parentedBy(FeedEvent event, String traceparent) {
        Correlation.TraceParent parent = Correlation.parseTraceparent(traceparent);
        if (parent == null) {
            return event;
        }
        return new FeedEvent(event.sequence(), event.id(), event.type(), event.occurredAt(), event.orderId(),
                event.username(), event.data(), event.requestId(), parent.traceId(), parent.parentId());
    }
}
