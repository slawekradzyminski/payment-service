package com.awesome.payment.support;

import com.awesome.payment.client.BackendClient;
import com.awesome.payment.client.BackendErrorMapper;
import com.awesome.payment.client.BackendException;
import com.awesome.payment.client.EventFeedPage;
import com.awesome.payment.client.FeedEvent;
import com.awesome.payment.client.InternalOrderDto;
import com.awesome.payment.client.JwkSet;
import com.awesome.payment.client.OrderStatus;
import com.awesome.payment.client.PaymentResultResponse;
import com.awesome.payment.correlation.Correlation;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-memory backend implementing the contract's payment-result rules (signature check with
 * five-minute skew, idempotency per requestId, amountDue check, PENDING to PAID, 409 "Order
 * is not payable") and the domain event feed ({@code GET /internal/v1/events}: sequence order,
 * {@code after}/{@code limit}/{@code types}, {@code nextCursor}/{@code hasMore}), so flow tests
 * run without Docker or HTTP. The signature is verified with an independent HMAC implementation.
 * Each call records the correlation the real {@code RestBackendClient} would send
 * ({@code X-Request-Id} and the trace id of the child {@code traceparent}), read from the MDC.
 */
public class FakeBackend implements BackendClient {

    private static final Pattern SIGNATURE = Pattern.compile("^t=(\\d+),v1=([0-9a-f]{64})$");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String webhookSecret;
    private final Clock clock;
    private final AtomicLong ids = new AtomicLong(1000);
    private final Map<Long, InternalOrderDto> orders = new ConcurrentHashMap<>();
    private final Map<Long, UUID> paidBy = new ConcurrentHashMap<>();
    private final Map<UUID, PaymentResultResponse> handled = new ConcurrentHashMap<>();
    private final List<Delivery> deliveries = new CopyOnWriteArrayList<>();
    private final Deque<Integer> scriptedResultStatuses = new ArrayDeque<>();
    private final List<FeedEvent> feed = new CopyOnWriteArrayList<>();
    private final List<FeedRequest> feedRequests = new CopyOnWriteArrayList<>();
    private final AtomicLong sequences = new AtomicLong();
    private volatile boolean available = true;
    private volatile long orderLookupDelayMillis;
    private volatile long resultDelayMillis;
    private volatile Runnable orderLookupProbe = () -> { };

    /**
     * One received result notification, as the backend would see it on the wire; {@code requestId}
     * and {@code traceId} are the correlation the call was made under (null outside any context).
     */
    public record Delivery(long orderId, String rawBody, String signature, long timestamp, boolean signatureValid,
                           JsonNode body, String requestId, String traceId) {
    }

    /** One {@code GET /internal/v1/events} call as the backend would see its query parameters. */
    public record FeedRequest(long after, int limit, List<String> types) {
    }

    public FakeBackend() {
        this("test-webhook-secret", Clock.systemUTC());
    }

    public FakeBackend(String webhookSecret, Clock clock) {
        this.webhookSecret = webhookSecret;
        this.clock = clock;
    }

    public synchronized void reset() {
        orders.clear();
        paidBy.clear();
        handled.clear();
        deliveries.clear();
        scriptedResultStatuses.clear();
        feed.clear();
        feedRequests.clear();
        sequences.set(0);
        available = true;
        orderLookupDelayMillis = 0;
        resultDelayMillis = 0;
        orderLookupProbe = () -> { };
    }

    /** Runs {@code probe} on the calling thread during every order lookup. */
    public void setOrderLookupProbe(Runnable probe) {
        this.orderLookupProbe = probe;
    }

    /** Slows down result notifications, like a busy backend answering them. */
    public void setResultDelayMillis(long millis) {
        this.resultDelayMillis = millis;
    }

    /** Slows down order lookups, widening the window in which concurrent payments could race. */
    public void setOrderLookupDelayMillis(long millis) {
        this.orderLookupDelayMillis = millis;
    }

    public void setAvailable(boolean available) {
        this.available = available;
    }

    /** The next result notifications answer these statuses (5xx, 4xx or 0 for a connection failure) in order. */
    public synchronized void failNextResults(Integer... statuses) {
        scriptedResultStatuses.addAll(List.of(statuses));
    }

    public InternalOrderDto order(String username, String total) {
        return order(username, OrderStatus.PENDING, total, null);
    }

    public InternalOrderDto order(String username, OrderStatus status, String total, String amountDue) {
        long id = ids.incrementAndGet();
        InternalOrderDto order = new InternalOrderDto(id, username, status, new BigDecimal(total),
                amountDue == null ? null : new BigDecimal(amountDue));
        orders.put(id, order);
        return order;
    }

    public InternalOrderDto order(long id) {
        return orders.get(id);
    }

    public void setStatus(long orderId, OrderStatus status) {
        orders.computeIfPresent(orderId, (id, o) -> new InternalOrderDto(o.id(), o.username(), status, o.totalAmount(),
                o.amountDue()));
    }

    public List<Delivery> deliveries() {
        return List.copyOf(deliveries);
    }

    @Override
    public InternalOrderDto getOrder(long orderId) {
        call();
        orderLookupProbe.run();
        pause(orderLookupDelayMillis);
        InternalOrderDto order = orders.get(orderId);
        if (order == null) {
            throw new BackendException("Order not found", HttpStatus.NOT_FOUND, 404, null);
        }
        return order;
    }

    @Override
    public synchronized PaymentResultResponse sendPaymentResult(long orderId, String rawBody, String signature) {
        call();
        pause(resultDelayMillis);
        Integer scripted = scriptedResultStatuses.poll();
        if (scripted != null) {
            if (scripted == 0 || scripted >= 500 || scripted == 401) {
                throw scripted == 0 ? BackendErrorMapper.unavailable(new IllegalStateException("connection reset"))
                        : BackendErrorMapper.fromResponse(HttpStatus.valueOf(scripted), null);
            }
            throw BackendErrorMapper.fromResponse(HttpStatus.valueOf(scripted),
                    "{\"message\":\"Scripted rejection\"}".getBytes(StandardCharsets.UTF_8));
        }
        Matcher matcher = SIGNATURE.matcher(signature == null ? "" : signature);
        boolean valid = matcher.matches()
                && hmac(matcher.group(1) + "." + rawBody).equals(matcher.group(2))
                && Math.abs(clock.instant().getEpochSecond() - Long.parseLong(matcher.group(1))) <= 300;
        JsonNode body = JSON.readTree(rawBody);
        deliveries.add(new Delivery(orderId, rawBody, signature, matcher.matches() ? Long.parseLong(matcher.group(1)) : -1,
                valid, body, Correlation.currentRequestId(), Correlation.currentTraceId()));
        if (!valid) {
            throw BackendErrorMapper.fromResponse(HttpStatus.UNAUTHORIZED, null);
        }
        UUID requestId = UUID.fromString(body.get("requestId").asString());
        PaymentResultResponse earlier = handled.get(requestId);
        if (earlier != null) {
            return earlier;
        }
        InternalOrderDto order = getOrder(orderId);
        UUID paymentId = UUID.fromString(body.get("paymentId").asString());
        if ("CAPTURED".equals(body.get("status").asString())) {
            if (order.status() == OrderStatus.PAID && paymentId.equals(paidBy.get(orderId))) {
                return remember(requestId, order);
            }
            if (order.status() != OrderStatus.PENDING) {
                throw new BackendException("Order is not payable", HttpStatus.CONFLICT, 409, null);
            }
            if (body.get("amount").decimalValue().compareTo(order.payableAmount()) != 0) {
                throw new BackendException("Amount does not match the amount due", HttpStatus.UNPROCESSABLE_CONTENT,
                        422, null);
            }
            setStatus(orderId, OrderStatus.PAID);
            paidBy.put(orderId, paymentId);
        }
        return remember(requestId, getOrder(orderId));
    }

    private static void pause(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Appends a domain event to the feed and returns it (sequence assigned in order). */
    public FeedEvent publish(String type, Long orderId, Map<String, Object> data) {
        return publish(UUID.randomUUID(), type, orderId, data);
    }

    public FeedEvent publish(UUID eventId, String type, Long orderId, Map<String, Object> data) {
        return publish(eventId, type, orderId, data, null, null);
    }

    /** Appends an event written by a request with these correlation ids (as the backend's V17 feed does). */
    public FeedEvent publish(UUID eventId, String type, Long orderId, Map<String, Object> data, String requestId,
                             String traceId) {
        FeedEvent event = new FeedEvent(sequences.incrementAndGet(), eventId, type, clock.instant().toString(), orderId,
                "client", JSON.valueToTree(data), requestId, traceId);
        feed.add(event);
        return event;
    }

    /** Re-appends an already published event (same id) under a new sequence, like a redelivery. */
    public FeedEvent republish(FeedEvent event) {
        FeedEvent copy = new FeedEvent(sequences.incrementAndGet(), event.id(), event.type(), event.occurredAt(),
                event.orderId(), event.username(), event.data(), event.requestId(), event.traceId());
        feed.add(copy);
        return copy;
    }

    public List<FeedRequest> feedRequests() {
        return List.copyOf(feedRequests);
    }

    /**
     * Matching events after {@code after}, at most {@code limit}. {@code nextCursor} is the last
     * returned sequence on a full page, otherwise the feed's last sequence (events of other types
     * are skipped over); {@code hasMore} is true when matching events remain.
     */
    @Override
    public EventFeedPage events(long after, int limit, List<String> types) {
        call();
        feedRequests.add(new FeedRequest(after, limit, List.copyOf(types)));
        List<FeedEvent> matching = feed.stream()
                .filter(e -> e.sequence() > after && (types.isEmpty() || types.contains(e.type())))
                .toList();
        List<FeedEvent> page = matching.stream().limit(limit).toList();
        boolean hasMore = matching.size() > page.size();
        long next = hasMore ? page.getLast().sequence() : Math.max(after, sequences.get());
        return new EventFeedPage(page, next, hasMore);
    }

    @Override
    public JwkSet jwks() {
        call();
        return TestTokens.jwks();
    }

    private PaymentResultResponse remember(UUID requestId, InternalOrderDto order) {
        PaymentResultResponse response = new PaymentResultResponse(order.id(), order.status());
        handled.put(requestId, response);
        return response;
    }

    private void call() {
        if (!available) {
            throw BackendErrorMapper.unavailable(new IllegalStateException("fake backend down"));
        }
    }

    private String hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
