package com.awesome.payment.correlation;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * Propagates the current correlation to the backend: the same {@code X-Request-Id} and, with
 * tracing off, a hand-built child {@code traceparent} (same trace id, a new parent id for this
 * call). With tracing on, the {@code traceparent} is the one the instrumented {@code RestClient}
 * set for its client span and is left alone (lab contract section 4). A call made without a
 * correlation context (a scheduled feed poll) starts a fresh request id (and, tracing off, trace).
 */
public class CorrelationInterceptor implements ClientHttpRequestInterceptor {

    private final boolean tracingEnabled;

    public CorrelationInterceptor(boolean tracingEnabled) {
        this.tracingEnabled = tracingEnabled;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        String requestId = Correlation.currentRequestId();
        request.getHeaders().set(Correlation.REQUEST_ID_HEADER,
                requestId == null ? Correlation.newRequestId() : requestId);
        if (!tracingEnabled) {
            String traceId = Correlation.currentTraceId();
            request.getHeaders().set(Correlation.TRACEPARENT_HEADER, Correlation.traceparent(
                    traceId == null ? Correlation.newTraceId() : traceId, Correlation.newSpanId()));
        }
        return execution.execute(request, body);
    }
}
