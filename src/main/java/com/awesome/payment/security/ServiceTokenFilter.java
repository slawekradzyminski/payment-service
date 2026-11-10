package com.awesome.payment.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Guards the service-to-service API under {@code /internal/}: the backend sends the shared
 * {@code X-Service-Token}. A blank configured token disables the API (404, as if it did not
 * exist); a missing or wrong token is 401. The comparison is constant-time.
 */
public class ServiceTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Service-Token";
    static final String INTERNAL_PREFIX = "/internal/";

    private final byte[] expected;

    public ServiceTokenFilter(String serviceToken) {
        super();
        this.expected = serviceToken == null || serviceToken.isBlank()
                ? new byte[0] : serviceToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().substring(request.getContextPath().length()).startsWith(INTERNAL_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (expected.length == 0) {
            ErrorResponses.send(response, HttpStatus.NOT_FOUND, "Not found");
            return;
        }
        if (!matches(request.getHeader(HEADER))) {
            ErrorResponses.send(response, HttpStatus.UNAUTHORIZED, "Invalid service token");
            return;
        }
        filterChain.doFilter(request, response);
    }

    boolean matches(String presented) {
        byte[] actual = presented == null ? new byte[0] : presented.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }
}
