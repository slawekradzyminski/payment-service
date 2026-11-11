package com.awesome.payment.correlation;

import org.slf4j.MDC;

import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Correlation ids shared by every hop (contract section "Correlation IDs"): the {@code X-Request-Id}
 * header (1-128 characters of {@code [A-Za-z0-9._-]}) and the W3C {@code traceparent}
 * ({@code 00-<32 hex trace id>-<16 hex parent id>-<2 hex flags>}, neither id all zeros). While a
 * request, a scheduled delivery or a feed event is handled, the MDC holds {@value #REQUEST_ID},
 * {@value #TRACE_ID} and {@value #SPAN_ID} (this hop's span), so every log line carries them.
 */
public final class Correlation {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String TRACEPARENT_HEADER = "traceparent";

    /** MDC keys (also the JSON field names of structured logs). */
    public static final String REQUEST_ID = "requestId";
    public static final String TRACE_ID = "traceId";
    public static final String SPAN_ID = "spanId";

    private static final Pattern REQUEST_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,128}$");
    private static final Pattern TRACEPARENT_PATTERN =
            Pattern.compile("^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$");
    private static final Pattern TRACE_ID_PATTERN = Pattern.compile("^[0-9a-f]{32}$");
    private static final String SAMPLED = "01";
    private static final HexFormat HEX = HexFormat.of();

    private Correlation() {
    }

    /** A parsed, valid {@code traceparent}. */
    public record TraceParent(String traceId, String parentId, String flags) {
    }

    /** {@code candidate} when it is a valid request id, otherwise null. */
    public static String validRequestId(String candidate) {
        return candidate != null && REQUEST_ID_PATTERN.matcher(candidate).matches() ? candidate : null;
    }

    /** {@code candidate} when it is a valid trace id (32 lower-case hex, not all zeros), otherwise null. */
    public static String validTraceId(String candidate) {
        return candidate != null && TRACE_ID_PATTERN.matcher(candidate).matches() && !allZeros(candidate)
                ? candidate : null;
    }

    /** The parsed header, or null when it is missing or invalid. */
    public static TraceParent parseTraceparent(String header) {
        if (header == null) {
            return null;
        }
        Matcher matcher = TRACEPARENT_PATTERN.matcher(header);
        if (!matcher.matches() || allZeros(matcher.group(1)) || allZeros(matcher.group(2))) {
            return null;
        }
        return new TraceParent(matcher.group(1), matcher.group(2), matcher.group(3));
    }

    /** The trace id of a valid {@code traceparent}, otherwise null. */
    public static String traceIdOf(String traceparent) {
        TraceParent parsed = parseTraceparent(traceparent);
        return parsed == null ? null : parsed.traceId();
    }

    public static String newRequestId() {
        return UUID.randomUUID().toString();
    }

    public static String newTraceId() {
        return randomHex(16);
    }

    public static String newSpanId() {
        return randomHex(8);
    }

    /** {@code 00-<traceId>-<spanId>-01}. */
    public static String traceparent(String traceId, String spanId) {
        return "00-" + traceId + "-" + spanId + "-" + SAMPLED;
    }

    public static String currentRequestId() {
        return MDC.get(REQUEST_ID);
    }

    public static String currentTraceId() {
        return MDC.get(TRACE_ID);
    }

    /** The {@code traceparent} of the current span (this hop), or null outside a traced context. */
    public static String currentTraceparent() {
        String traceId = MDC.get(TRACE_ID);
        String spanId = MDC.get(SPAN_ID);
        return traceId == null || spanId == null ? null : traceparent(traceId, spanId);
    }

    /**
     * Puts {@code requestId} and {@code traceId} (with a new span id) into the MDC; a null value
     * removes the key. Closing the scope restores what was there before.
     */
    public static Scope open(String requestId, String traceId) {
        Scope scope = new Scope(MDC.get(REQUEST_ID), MDC.get(TRACE_ID), MDC.get(SPAN_ID));
        set(REQUEST_ID, requestId);
        set(TRACE_ID, traceId);
        set(SPAN_ID, traceId == null ? null : newSpanId());
        return scope;
    }

    /** Restores the MDC correlation keys on close. */
    public static final class Scope implements AutoCloseable {

        private final String requestId;
        private final String traceId;
        private final String spanId;

        private Scope(String requestId, String traceId, String spanId) {
            this.requestId = requestId;
            this.traceId = traceId;
            this.spanId = spanId;
        }

        @Override
        public void close() {
            set(REQUEST_ID, requestId);
            set(TRACE_ID, traceId);
            set(SPAN_ID, spanId);
        }
    }

    private static void set(String key, String value) {
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }

    private static String randomHex(int bytes) {
        byte[] random = new byte[bytes];
        do {
            ThreadLocalRandom.current().nextBytes(random);
        } while (allZeros(HEX.formatHex(random)));
        return HEX.formatHex(random);
    }

    private static boolean allZeros(String hex) {
        return hex.chars().allMatch(c -> c == '0');
    }
}
