package com.awesome.payment.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    private MockRestServiceServer server;
    private RestBackendClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestBackendClient(builder, BASE, TOKEN);
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
                .andExpect(content().string(RESULT_BODY))
                .andRespond(withSuccess("{\"orderId\":42,\"orderStatus\":\"PAID\"}", MediaType.APPLICATION_JSON));

        PaymentResultResponse response = client.sendPaymentResult(42, RESULT_BODY, "t=1,v1=abc");

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
