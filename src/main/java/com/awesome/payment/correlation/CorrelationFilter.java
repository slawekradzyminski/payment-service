package com.awesome.payment.correlation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = Correlation.validRequestId(request.getHeader(Correlation.REQUEST_ID_HEADER));
        if (requestId == null) {
            requestId = Correlation.newRequestId();
        }
        Correlation.TraceParent parent = Correlation.parseTraceparent(
                request.getHeader(Correlation.TRACEPARENT_HEADER));
        String traceId = parent == null ? Correlation.newTraceId() : parent.traceId();
        response.setHeader(Correlation.REQUEST_ID_HEADER, requestId);
        try (Correlation.Scope ignored = Correlation.open(requestId, traceId)) {
            chain.doFilter(request, response);
        }
    }
}
