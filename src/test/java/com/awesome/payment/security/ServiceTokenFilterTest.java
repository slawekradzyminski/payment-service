package com.awesome.payment.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceTokenFilterTest {

    private static final String PATH = "/internal/v1/payments/refunds";

    @Test
    void theRightTokenPassesThrough() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = run(new ServiceTokenFilter("secret-token"), PATH, "secret-token", chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void missingOrWrongTokenIs401() throws Exception {
        ServiceTokenFilter filter = new ServiceTokenFilter("secret-token");
        for (String token : new String[] {null, "", "secret-toke", "secret-token ", "SECRET-TOKEN"}) {
            MockFilterChain chain = new MockFilterChain();
            MockHttpServletResponse response = run(filter, PATH, token, chain);

            assertThat(chain.getRequest()).as(String.valueOf(token)).isNull();
            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getContentAsString()).isEqualTo("{\"message\":\"Invalid service token\"}");
        }
    }

    @Test
    void aBlankConfiguredTokenHidesTheInternalApi() throws Exception {
        for (String configured : new String[] {null, "", "  "}) {
            MockFilterChain chain = new MockFilterChain();
            MockHttpServletResponse response = run(new ServiceTokenFilter(configured), PATH, "", chain);

            assertThat(chain.getRequest()).isNull();
            assertThat(response.getStatus()).isEqualTo(404);
            assertThat(response.getContentAsString()).isEqualTo("{\"message\":\"Not found\"}");
        }
    }

    @Test
    void publicPathsAreNotFiltered() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = run(new ServiceTokenFilter("secret-token"), "/api/v1/payments", null, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
        MockFilterChain internalLookalike = new MockFilterChain();
        run(new ServiceTokenFilter("secret-token"), "/internalx", null, internalLookalike);
        assertThat(internalLookalike.getRequest()).isNotNull();
    }

    @Test
    void theContextPathIsIgnored() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/ctx" + PATH);
        request.setContextPath("/ctx");
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ServiceTokenFilter("secret-token").doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
    }

    private static MockHttpServletResponse run(ServiceTokenFilter filter, String path, String token, MockFilterChain chain)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        if (token != null) {
            request.addHeader(ServiceTokenFilter.HEADER, token);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }
}
