package com.awesome.payment.events;

import java.util.Locale;

/**
 * How this service receives the backend's commerce events, selected by {@code payment.events.transport}
 * ({@code EVENTS_TRANSPORT}): the feed poller or the broker listener, never both.
 */
public enum EventTransport {
    FEED,
    BROKER;

    public static EventTransport from(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "feed" -> FEED;
            case "broker" -> BROKER;
            default -> throw new IllegalArgumentException(
                    "payment.events.transport must be 'feed' or 'broker' but was '" + value + "'");
        };
    }
}
