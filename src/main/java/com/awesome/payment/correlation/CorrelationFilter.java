package com.awesome.payment.correlation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.awesome.payment.tracing.Spans;
import io.micrometer.tracing.TraceContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * First filter of every request: keeps a valid incoming {@code X-Request-Id} (otherwise generates
 * one) and the trace id of a valid incoming {@code traceparent} (otherwise starts a new trace),
 * opens a new span for this hop, puts all three into the MDC for the request, answers the request
 * id in the {@code X-Request-Id} response header and clears the MDC afterwards. It runs before
 * Spring Security, so 401/403 answers carry the header and their log lines the ids.
 *
 * <p>It runs right after Spring's {@code ServerHttpObservationFilter} ({@code HIGHEST_PRECEDENCE + 1}),
 * so with tracing on the HTTP server span, already parented by the incoming {@code traceparent},
 * supplies the trace and span id instead (same trace id whenever the caller sent a valid one).
 */
@Component
@Order(CorrelationFilter.ORDER)
public class CorrelationFilter extends OncePerRequestFilter {

    /** Right after the server observation filter, before Spring Security and everything else. */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 2;

    private final Spans spans;

    /** Tracing off (unit tests). */
    public CorrelationFilter() {
        this(Spans.disabled());
    }

    @Autowired
    public CorrelationFilter(Spans spans) {
        super();
        this.spans = spans;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = Correlation.validRequestId(request.getHeader(Correlation.REQUEST_ID_HEADER));
        if (requestId == null) {
            requestId = Correlation.newRequestId();
        }
        response.setHeader(Correlation.REQUEST_ID_HEADER, requestId);
        try (Correlation.Scope ignored = open(requestId, request.getHeader(Correlation.TRACEPARENT_HEADER))) {
            chain.doFilter(request, response);
        }
    }

    private Correlation.Scope open(String requestId, String traceparent) {
        TraceContext serverSpan = spans.current();
        if (serverSpan != null) {
            return Correlation.open(requestId, serverSpan.traceId(), serverSpan.spanId());
        }
        Correlation.TraceParent parent = Correlation.parseTraceparent(traceparent);
        return Correlation.open(requestId, parent == null ? Correlation.newTraceId() : parent.traceId());
    }
}
