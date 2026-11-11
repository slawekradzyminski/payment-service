package com.awesome.payment.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Body of {@code GET /internal/v1/events}: events ordered by sequence ascending.
 *
 * @param nextCursor the {@code after} value for the next request; it may lie beyond the last
 *                   returned event when the backend skipped events of other types
 * @param hasMore    more matching events are waiting after {@code nextCursor}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventFeedPage(List<FeedEvent> events, long nextCursor, boolean hasMore) {

    public EventFeedPage {
        events = events == null ? List.of() : List.copyOf(events);
    }
}
