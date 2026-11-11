package com.awesome.payment.client;

import java.util.List;

/**
 * Backend internal API ({@code /internal/v1}). Implementations translate backend
 * failures into {@link BackendException}s: a 4xx keeps its status and message, any
 * unavailability becomes 503 "Backend unavailable".
 */
public interface BackendClient {

    /** Order snapshot ({@code GET /internal/v1/orders/{id}}). */
    InternalOrderDto getOrder(long orderId);

    /**
     * Delivers a payment result ({@code POST /internal/v1/orders/{id}/payment}). The body is
     * sent byte for byte as given, because {@code signature} covers exactly those bytes.
     */
    PaymentResultResponse sendPaymentResult(long orderId, String rawBody, String signature);

    /** The backend's access-token verification keys. */
    JwkSet jwks();

    /**
     * One page of the backend's domain event feed ({@code GET /internal/v1/events}): events with a
     * sequence greater than {@code after}, at most {@code limit}, restricted to the given wire names.
     */
    EventFeedPage events(long after, int limit, List<String> types);
}
