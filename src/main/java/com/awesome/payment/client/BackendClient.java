package com.awesome.payment.client;

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
}
