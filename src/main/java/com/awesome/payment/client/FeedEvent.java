package com.awesome.payment.client;

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
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FeedEvent(
        long sequence,
        UUID id,
        String type,
        String occurredAt,
        Long orderId,
        String username,
        JsonNode data) {
}
