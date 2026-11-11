package com.awesome.payment.events;

import com.awesome.payment.client.FeedEvent;

/**
 * Handles one event type of the backend feed. Runs inside the poller's per-event transaction,
 * so it must not commit on its own. Throwing a non-transient exception skips the event; a
 * {@link org.springframework.dao.TransientDataAccessException} or a transient
 * {@link com.awesome.payment.client.BackendException} leaves it for the next poll.
 */
public interface FeedEventHandler {

    /** The wire name this handler consumes, sent in the feed's {@code types} filter. */
    String type();

    void handle(FeedEvent event);
}
