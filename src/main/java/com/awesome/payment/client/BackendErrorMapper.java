package com.awesome.payment.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Translates backend internal-API failures into errors for the payment-service
 * caller: a 4xx keeps its status and the backend's {@code message}; a 5xx or a
 * rejected service token means the backend cannot serve payment requests and
 * becomes 503 "Backend unavailable". The backend's own status is kept on the
 * {@link BackendException} for the result outbox's retry decision.
 */
@Slf4j
public final class BackendErrorMapper {

    public static final String BACKEND_UNAVAILABLE = "Backend unavailable";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private BackendErrorMapper() {
    }

    public static BackendException fromResponse(HttpStatusCode status, byte[] body) {
        int code = status.value();
        if (code == HttpStatus.UNAUTHORIZED.value()) {
            // The caller's JWT is not forwarded, so a 401 means our service token or signature was rejected.
            log.error("Backend rejected the payment service token or signature; check PAYMENT_SERVICE_TOKEN "
                    + "and PAYMENT_WEBHOOK_SECRET");
            return new BackendException(BACKEND_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE, code, null);
        }
        if (!status.is4xxClientError()) {
            log.error("Backend internal API failed with status {}", code);
            return new BackendException(BACKEND_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE, code, null);
        }
        HttpStatus resolved = HttpStatus.resolve(code);
        HttpStatus httpStatus = resolved == null ? HttpStatus.BAD_REQUEST : resolved;
        return new BackendException(message(body, httpStatus), httpStatus, code, null);
    }

    public static BackendException unavailable(Throwable cause) {
        if (cause != null) {
            log.error("Backend internal API is unreachable: {}", cause.getMessage());
        }
        return new BackendException(BACKEND_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE, 0, cause);
    }

    static String message(byte[] body, HttpStatus status) {
        if (body == null || body.length == 0) {
            return status.getReasonPhrase();
        }
        try {
            JsonNode message = JSON.readTree(body).get("message");
            if (message != null && message.isString() && !message.asString().isBlank()) {
                return message.asString();
            }
        } catch (JacksonException e) {
            log.debug("Backend error body is not JSON", e);
        }
        return status.getReasonPhrase();
    }
}
