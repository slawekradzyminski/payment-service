package com.awesome.payment.events;

import com.awesome.payment.client.FeedEvent;
import com.awesome.payment.service.RefundService;
import com.awesome.payment.service.RefundService.CardRefundRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * {@code card-refund.requested}, data {@code {requestId, orderId, amount, reason}}: books the
 * card refund with {@link RefundService} (idempotent per requestId; no captured payment and an
 * amount above the refundable rest are logged and book nothing). A malformed payload throws
 * {@link IllegalArgumentException}, so the poller logs and skips the event.
 */
@Component
@RequiredArgsConstructor
public class CardRefundRequestedHandler implements FeedEventHandler {

    public static final String EVENT_TYPE = "card-refund.requested";
    static final int MAX_REASON_LENGTH = 255;

    private final RefundService refundService;

    @Override
    public String type() {
        return EVENT_TYPE;
    }

    @Override
    public void handle(FeedEvent event) {
        refundService.refund(request(event));
    }

    static CardRefundRequest request(FeedEvent event) {
        JsonNode data = event.data();
        if (data == null || !data.isObject()) {
            throw new IllegalArgumentException("card-refund.requested event has no data");
        }
        UUID requestId = UUID.fromString(text(data, "requestId"));
        long orderId = data.hasNonNull("orderId") ? Long.parseLong(text(data, "orderId")) : orderIdOf(event);
        BigDecimal amount = new BigDecimal(text(data, "amount")).setScale(2, RoundingMode.HALF_UP);
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("card-refund.requested amount must be positive: " + amount);
        }
        JsonNode reasonNode = data.get("reason");
        String reason = reasonNode == null || reasonNode.isNull() ? null : reasonNode.asString();
        if (reason != null && reason.length() > MAX_REASON_LENGTH) {
            reason = reason.substring(0, MAX_REASON_LENGTH);
        }
        return new CardRefundRequest(requestId, orderId, amount, reason);
    }

    private static long orderIdOf(FeedEvent event) {
        if (event.orderId() == null) {
            throw new IllegalArgumentException("card-refund.requested event has no orderId");
        }
        return event.orderId();
    }

    private static String text(JsonNode data, String field) {
        JsonNode node = data.get(field);
        if (node == null || node.isNull() || !(node.isNumber() || node.isString())) {
            throw new IllegalArgumentException("card-refund.requested event has no " + field);
        }
        return node.isNumber() ? node.decimalValue().toPlainString() : node.asString();
    }
}
