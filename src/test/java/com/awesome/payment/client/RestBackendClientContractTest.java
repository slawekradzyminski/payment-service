package com.awesome.payment.client;

import com.awesome.payment.correlation.Correlation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Pins the exact internal-API requests (method, path, headers, body) the backend must accept. */
class RestBackendClientContractTest {

    private static final String BASE = "http://backend:4001";
    private static final String TOKEN = "test-service-token";
    private static final String ORDER_JSON = """
            {"id":42,"username":"client","status":"PENDING","fulfilmentState":"NONE",
             "subtotalAmount":140.00,"discountAmount":14.00,"totalAmount":126.00,"amountDue":100.50,
             "couponCode":"TENOFF","refundedAmount":0.00,
             "shippingAddress":{"street":"1 Main St","city":"Krakow","state":"MA","zipCode":"30-001","country":"Poland"},
             "items":[{"productId":3,"productName":"Phone","quantity":2,"unitPrice":70.00}],
             "createdAt":"2026-11-08T10:15:30","updatedAt":"2026-11-08T10:16:00"}""";
    private static final String RESULT_BODY = "{\"requestId\":\"00000000-0000-0000-0000-000000000001\","
            + "\"paymentId\":\"00000000-0000-0000-0000-000000000002\",\"status\":\"CAPTURED\",\"amount\":126.00,"
            + "\"currency\":\"USD\",\"failureCode\":null}";

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String UUID_PATTERN = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";
    private static final String TRACEPARENT_PATTERN = "^00-[0-9a-f]{32}-[0-9a-f]{16}-01$";

    private MockRestServiceServer server;
    private RestBackendClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestBackendClient(builder, BASE, TOKEN);
    }

    @AfterEach
    void clearCorrelation() {
        MDC.clear();
    }

    @Test
    void getOrderSendsTheServiceTokenAndReadsTheAmountDue() {
        server.expect(requestTo(BASE + "/internal/v1/orders/42"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Service-Token", TOKEN))
                .andExpect(header("Accept", MediaType.APPLICATION_JSON_VALUE))
                .andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess(ORDER_JSON, MediaType.APPLICATION_JSON));

        InternalOrderDto order = client.getOrder(42);

        server.verify();
        assertThat(order.id()).isEqualTo(42L);
        assertThat(order.username()).isEqualTo("client");
        assertThat(order.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.totalAmount()).isEqualByComparingTo("126.00");
        assertThat(order.payableAmount()).isEqualByComparingTo("100.50");
    }

    @Test
    void ordersWithoutAmountDueAreChargedTheirTotal() {
        server.expect(requestTo(BASE + "/internal/v1/orders/7"))
                .andRespond(withSuccess("{\"id\":7,\"username\":\"client\",\"status\":\"PAID\",\"totalAmount\":9.99}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.getOrder(7).payableAmount()).isEqualByComparingTo("9.99");
    }

    @Test
    void paymentResultIsPostedVerbatimWithSignatureAndServiceToken() {
        server.expect(requestTo(BASE + "/internal/v1/orders/42/payment"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Service-Token", TOKEN))
                .andExpect(header("X-Payment-Signature", "t=1,v1=abc"))
                .andExpect(header("Content-Type", MediaType.APPLICATION_JSON_VALUE))
                .andExpect(header("X-Request-Id", "req-42"))
                .andExpect(header("traceparent", matchesPattern("^00-" + TRACE_ID + "-[0-9a-f]{16}-01$")))
                .andExpect(content().string(RESULT_BODY))
                .andRespond(withSuccess("{\"orderId\":42,\"orderStatus\":\"PAID\"}", MediaType.APPLICATION_JSON));

        PaymentResultResponse response;
        try (Correlation.Scope ignored = Correlation.open("req-42", TRACE_ID)) {
            response = client.sendPaymentResult(42, RESULT_BODY, "t=1,v1=abc");
        }

        server.verify();
        assertThat(response).isEqualTo(new PaymentResultResponse(42L, OrderStatus.PAID));
    }

    @Test
    void anEmptyResultResponseStillCountsAsDelivered() {
        server.expect(requestTo(BASE + "/internal/v1/orders/42/payment")).andRespond(withSuccess());

        assertThat(client.sendPaymentResult(42, RESULT_BODY, "t=1,v1=abc")).isNull();
    }

    @Test
    void jwksAreReadFromTheBackend() {
        server.expect(requestTo(BASE + "/internal/v1/jwks.json"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"k1\",\"alg\":\"RS256\",\"use\":\"sig\","
                        + "\"n\":\"AQAB\",\"e\":\"AQAB\"}]}", MediaType.APPLICATION_JSON));

        assertThat(client.jwks().keys()).singleElement().satisfies(key -> assertThat(key.kid()).isEqualTo("k1"));
    }

    @Test
    void theEventFeedIsReadFromTheCursorWithTheTypesFilter() {
        server.expect(requestTo(BASE + "/internal/v1/events?after=41&limit=100&types=card-refund.requested,order.cancelled"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Service-Token", TOKEN))
                .andExpect(header("Accept", MediaType.APPLICATION_JSON_VALUE))
                .andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess("""
                        {"events":[{"sequence":42,"id":"00000000-0000-0000-0000-0000000000e1",
                          "type":"card-refund.requested","occurredAt":"2026-11-10T12:00:00Z","orderId":7,
                          "username":"client","data":{"requestId":"00000000-0000-0000-0000-00000000a001",
                          "orderId":7,"amount":12.50,"reason":"Return #1"},"requestId":"gw-req-1",
                          "traceId":"4bf92f3577b34da6a3ce929d0e0e4736","unknown":true}],
                         "nextCursor":45,"hasMore":true}""", MediaType.APPLICATION_JSON));

        EventFeedPage page = client.events(41, 100, List.of("card-refund.requested", "order.cancelled"));

        server.verify();
        assertThat(page.nextCursor()).isEqualTo(45L);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.events()).singleElement().satisfies(event -> {
            assertThat(event.sequence()).isEqualTo(42L);
            assertThat(event.id()).isEqualTo(UUID.fromString("00000000-0000-0000-0000-0000000000e1"));
            assertThat(event.type()).isEqualTo("card-refund.requested");
            assertThat(event.occurredAt()).isEqualTo("2026-11-10T12:00:00Z");
            assertThat(event.orderId()).isEqualTo(7L);
            assertThat(event.username()).isEqualTo("client");
            assertThat(event.data().get("amount").decimalValue()).isEqualByComparingTo("12.50");
            assertThat(event.data().get("requestId").asString()).isEqualTo("00000000-0000-0000-0000-00000000a001");
            assertThat(event.requestId()).isEqualTo("gw-req-1");
            assertThat(event.traceId()).isEqualTo(TRACE_ID);
        });
    }

    @Test
    void feedEventsWithoutCorrelationIdsAreStillRead() {
        server.expect(requestTo(BASE + "/internal/v1/events?after=0&limit=100&types=card-refund.requested"))
                .andRespond(withSuccess("""
                        {"events":[{"sequence":1,"id":"00000000-0000-0000-0000-0000000000e1",
                          "type":"card-refund.requested","orderId":7,"data":{}}],"nextCursor":1,"hasMore":false}""",
                        MediaType.APPLICATION_JSON));

        assertThat(client.events(0, 100, List.of("card-refund.requested")).events()).singleElement()
                .satisfies(event -> {
                    assertThat(event.requestId()).isNull();
                    assertThat(event.traceId()).isNull();
                });
    }

    @Test
    void everyCallCarriesTheCurrentRequestIdAndANewChildSpanOfTheCurrentTrace() {
        List<String> traceparents = new ArrayList<>();
        String expectedTraceparent = "^00-" + TRACE_ID + "-[0-9a-f]{16}-01$";
        server.expect(requestTo(BASE + "/internal/v1/orders/42"))
                .andExpect(header("X-Request-Id", "gw.req_42-A"))
                .andExpect(header("traceparent", matchesPattern(expectedTraceparent)))
                .andExpect(request -> traceparents.add(request.getHeaders().getFirst("traceparent")))
                .andRespond(withSuccess(ORDER_JSON, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/internal/v1/events?after=0&limit=100&types=card-refund.requested"))
                .andExpect(header("X-Request-Id", "gw.req_42-A"))
                .andExpect(header("traceparent", matchesPattern(expectedTraceparent)))
                .andExpect(request -> traceparents.add(request.getHeaders().getFirst("traceparent")))
                .andRespond(withSuccess("{\"events\":[],\"nextCursor\":0,\"hasMore\":false}",
                        MediaType.APPLICATION_JSON));

        String ownSpan;
        try (Correlation.Scope ignored = Correlation.open("gw.req_42-A", TRACE_ID)) {
            ownSpan = MDC.get(Correlation.SPAN_ID);
            client.getOrder(42);
            client.events(0, 100, List.of("card-refund.requested"));
        }

        server.verify();
        assertThat(traceparents).hasSize(2).doesNotHaveDuplicates()
                .noneMatch(traceparent -> traceparent.contains(ownSpan));
    }

    @Test
    void aCallOutsideAnyRequestStartsAFreshRequestIdAndTrace() {
        server.expect(requestTo(BASE + "/internal/v1/jwks.json"))
                .andExpect(header("X-Request-Id", matchesPattern(UUID_PATTERN)))
                .andExpect(header("traceparent", matchesPattern(TRACEPARENT_PATTERN)))
                .andRespond(withSuccess("{\"keys\":[]}", MediaType.APPLICATION_JSON));

        client.jwks();

        server.verify();
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void anEmptyFeedPageHasNoEvents() {
        server.expect(requestTo(BASE + "/internal/v1/events?after=0&limit=100&types=card-refund.requested"))
                .andRespond(withSuccess("{\"events\":[],\"nextCursor\":0,\"hasMore\":false}", MediaType.APPLICATION_JSON));

        EventFeedPage page = client.events(0, 100, List.of("card-refund.requested"));

        assertThat(page.events()).isEmpty();
        assertThat(page.hasMore()).isFalse();
        assertThat(new EventFeedPage(null, 0, false).events()).isEmpty();
    }

    @Test
    void anUnavailableFeedIsTransient() {
        server.expect(requestTo(BASE + "/internal/v1/events?after=0&limit=100&types=card-refund.requested"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.events(0, 100, List.of("card-refund.requested")))
                .isInstanceOfSatisfying(BackendException.class, e -> assertThat(e.isTransient()).isTrue());
    }

    @Test
    void notPayableKeepsStatusMessageAndBackendStatus() {
        server.expect(requestTo(BASE + "/internal/v1/orders/42/payment"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"Order is not payable\"}"));

        assertThatThrownBy(() -> client.sendPaymentResult(42, RESULT_BODY, "t=1,v1=abc"))
                .isInstanceOfSatisfying(BackendException.class, e -> {
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getBackendStatus()).isEqualTo(409);
                    assertThat(e.getMessage()).isEqualTo("Order is not payable");
                    assertThat(e.isTransient()).isFalse();
                });
    }

    @Test
    void serverErrorsAndConnectionFailuresAreTransient() {
        server.expect(requestTo(BASE + "/internal/v1/orders/1")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        assertThatThrownBy(() -> client.getOrder(1)).isInstanceOfSatisfying(BackendException.class, e -> {
            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(e.getBackendStatus()).isEqualTo(502);
            assertThat(e.isTransient()).isTrue();
        });

        server.reset();
        server.expect(requestTo(BASE + "/internal/v1/orders/1")).andRespond(withException(new IOException("reset")));
        assertThatThrownBy(() -> client.getOrder(1)).isInstanceOfSatisfying(BackendException.class, e -> {
            assertThat(e.getBackendStatus()).isZero();
            assertThat(e.isTransient()).isTrue();
        });
    }

    @Test
    void anEmptyOrderBodyMeansTheBackendIsUnavailable() {
        server.expect(requestTo(BASE + "/internal/v1/orders/1")).andRespond(withSuccess());

        assertThatThrownBy(() -> client.getOrder(1)).isInstanceOfSatisfying(BackendException.class,
                e -> assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void aBlankServiceTokenSendsNoTokenHeader() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer blankServer = MockRestServiceServer.bindTo(builder).build();
        RestBackendClient blank = new RestBackendClient(builder, BASE, " ");
        blankServer.expect(requestTo(BASE + "/internal/v1/orders/42"))
                .andExpect(headerDoesNotExist("X-Service-Token"))
                .andRespond(withSuccess(ORDER_JSON, MediaType.APPLICATION_JSON));

        assertThat(blank.getOrder(42).id()).isEqualTo(42L);
        blankServer.verify();
    }
}
