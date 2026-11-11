package com.awesome.payment.correlation;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorrelationFilterTest {

    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String PARENT = "00f067aa0ba902b7";

    private final CorrelationFilter filter = new CorrelationFilter();
    private final Map<String, String> seen = new HashMap<>();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void validIncomingIdsAreKeptWithANewSpanAndTheMdcIsClearedAfterwards() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/payments/x");
        request.addHeader("X-Request-Id", "gw-req.1_A");
        request.addHeader("traceparent", "00-" + TRACE + "-" + PARENT + "-01");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, recordingChain());

        assertThat(seen).containsEntry("requestId", "gw-req.1_A").containsEntry("traceId", TRACE);
        assertThat(seen.get("spanId")).matches("^[0-9a-f]{16}$").isNotEqualTo(PARENT);
        assertThat(response.getHeader("X-Request-Id")).isEqualTo("gw-req.1_A");
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void missingIdsAreGenerated() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/"), response, recordingChain());

        assertThat(Correlation.validRequestId(seen.get("requestId"))).isNotNull();
        assertThat(Correlation.validTraceId(seen.get("traceId"))).isNotNull();
        assertThat(response.getHeader("X-Request-Id")).isEqualTo(seen.get("requestId"));
    }

    @Test
    void invalidIdsAreReplaced() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        request.addHeader("X-Request-Id", "evil\r\nSet-Cookie: x=1");
        request.addHeader("traceparent", "00-" + "0".repeat(32) + "-" + PARENT + "-01");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, recordingChain());

        assertThat(seen.get("requestId")).doesNotContain("evil").matches("^[0-9a-f-]{36}$");
        assertThat(seen.get("traceId")).isNotEqualTo("0".repeat(32)).matches("^[0-9a-f]{32}$");
        assertThat(response.getHeader("X-Request-Id")).isEqualTo(seen.get("requestId"));
    }

    @Test
    void theMdcIsClearedEvenWhenTheChainFails() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        request.addHeader("X-Request-Id", "failing");

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            throw new ServletException("boom");
        })).isInstanceOf(ServletException.class);
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    private MockFilterChain recordingChain() {
        return new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response)
                    throws IOException, ServletException {
                seen.putAll(MDC.getCopyOfContextMap());
                super.doFilter(request, response);
            }
        };
    }
}
