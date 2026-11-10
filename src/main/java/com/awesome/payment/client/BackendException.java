package com.awesome.payment.client;

import com.awesome.payment.controller.exception.CustomException;
import org.springframework.http.HttpStatus;

import java.io.Serial;

/**
 * A failed backend internal-API call. {@link #getHttpStatus()} is what payment-service
 * answers its own caller; {@link #getBackendStatus()} is the backend's HTTP status, or 0
 * when no response arrived (connection failure, timeout, unreadable body).
 */
public class BackendException extends CustomException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final int backendStatus;

    public BackendException(String message, HttpStatus httpStatus, int backendStatus, Throwable cause) {
        super(message, httpStatus, cause);
        this.backendStatus = backendStatus;
    }

    public int getBackendStatus() {
        return backendStatus;
    }

    /** No response or a 5xx: the call may succeed later. */
    public boolean isTransient() {
        return backendStatus == 0 || backendStatus >= HttpStatus.INTERNAL_SERVER_ERROR.value();
    }
}
