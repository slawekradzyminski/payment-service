package com.awesome.payment.client;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * One backend domain event from the event feed.
 *
 * @param sequence   the feed position; the consumer's cursor is the last handled sequence
 * @param id         unique event id, recorded in {@code processed_events} for idempotency
 * @param type       wire name, for example {@code card-refund.requested}
 * @param occurredAt when the change happened, as the backend formats it (informational only)
 * @param data       type-specific payload
 * @param requestId  {@code X-Request-Id} of the request whose transaction wrote the event (optional)
 * @param traceId    trace id of that request (optional); both are set in the MDC while the event is handled
 * @param spanId     span id of the backend span that wrote the event (optional, backend 3.21.0+); with
 *                   tracing on, the consumer span {@code process <type>} is its child
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FeedEvent(
        long sequence,
        UUID id,
        String type,
        String occurredAt,
        Long orderId,
        String username,
        JsonNode data,
        String requestId,
        String traceId,
        String spanId) {

    @JsonCreator
    public FeedEvent {
        // canonical constructor: the one Jackson binds the feed's JSON to
    }

    /** An event without correlation ids (written by a scheduled backend job, or by an older backend). */
    public FeedEvent(long sequence, UUID id, String type, String occurredAt, Long orderId, String username,
                     JsonNode data) {
        this(sequence, id, type, occurredAt, orderId, username, data, null, null, null);
    }
}
