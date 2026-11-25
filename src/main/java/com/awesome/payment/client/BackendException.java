package com.awesome.payment.client;

import com.awesome.payment.controller.exception.CustomException;
import org.springframework.http.HttpStatus;

import java.io.Serial;
import java.util.Set;

/**
 * A failed backend internal-API call. {@link #getHttpStatus()} is what payment-service
 * answers its own caller; {@link #getBackendStatus()} is the backend's HTTP status, or 0
 * when no response arrived (connection failure, timeout, unreadable body).
 */
public class BackendException extends CustomException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 4xx answers that say nothing about the request itself: a rejected service token or signature
     * (401/403, e.g. while PAYMENT_SERVICE_TOKEN or PAYMENT_WEBHOOK_SECRET is being rotated), a
     * request timeout (408) and rate limiting (429). The same request may succeed later.
     */
    private static final Set<Integer> RETRYABLE_CLIENT_ERRORS = Set.of(
            HttpStatus.UNAUTHORIZED.value(), HttpStatus.FORBIDDEN.value(),
            HttpStatus.REQUEST_TIMEOUT.value(), HttpStatus.TOO_MANY_REQUESTS.value());

    private final int backendStatus;

    public BackendException(String message, HttpStatus httpStatus, int backendStatus, Throwable cause) {
        super(message, httpStatus, cause);
        this.backendStatus = backendStatus;
    }

    public int getBackendStatus() {
        return backendStatus;
    }

    /** No response, a 5xx, or a 401, 403, 408 or 429: the same call may succeed later. */
    public boolean isTransient() {
        return backendStatus == 0 || backendStatus >= HttpStatus.INTERNAL_SERVER_ERROR.value()
                || RETRYABLE_CLIENT_ERRORS.contains(backendStatus);
    }
}
