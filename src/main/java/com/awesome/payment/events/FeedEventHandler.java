package com.awesome.payment.events;

import com.awesome.payment.client.FeedEvent;

/**
 * Handles one event type of the backend feed. Runs inside the poller's per-event transaction,
 * so it must not commit on its own. Throwing {@link IllegalArgumentException} (malformed payload)
 * or a final {@link com.awesome.payment.client.BackendException} rejection skips the event; any
 * other exception (database, transaction, transient backend or unexpected) leaves it for the next
 * poll ({@link EventFeedPoller#isPoisonEvent}).
 */
public interface FeedEventHandler {

    /** The wire name this handler consumes, sent in the feed's {@code types} filter. */
    String type();

    void handle(FeedEvent event);
}
