package com.awesome.payment.support;

import com.awesome.payment.dto.payment.PaymentDto;
import com.awesome.payment.repository.IdempotencyKeyRepository;
import com.awesome.payment.repository.PaymentOutboxRepository;
import com.awesome.payment.repository.PaymentRepository;
import com.awesome.payment.repository.RefundRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Boots the whole service on H2 with the in-memory backend and a hand-moved clock, driven over MockMvc. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(TestBackendConfig.class)
public abstract class AbstractFlowTest {

    protected static final String PAYMENTS = "/api/v1/payments";
    protected static final String REFUNDS = "/internal/v1/payments/refunds";
    protected static final String SERVICE_TOKEN = "test-service-token";

    protected static final String VISA = "4242 4242 4242 4242";
    protected static final String MASTERCARD = "5555 5555 5555 4444";
    protected static final String DECLINED = "4000 0000 0000 0002";
    protected static final String INSUFFICIENT_FUNDS = "4000 0000 0000 9995";
    protected static final String THREE_DS = "4000 0000 0000 3220";
    protected static final String SETTLES = "4000 0000 0000 0077";
    protected static final String TIMES_OUT = "4000 0000 0000 0119";
    protected static final String DUPLICATE_NOTIFICATION = "4000 0000 0000 0259";

    private static final AtomicInteger KEYS = new AtomicInteger();

    protected final String adminToken = TestTokens.token("admin", "ROLE_ADMIN");
    protected final String clientToken = TestTokens.token("client", "ROLE_CLIENT");
    protected final String otherClientToken = TestTokens.token("other", "ROLE_CLIENT");

    @Autowired protected MockMvc mockMvc;
    @Autowired protected JsonMapper jsonMapper;
    @Autowired protected FakeBackend backend;
    @Autowired protected MutableClock clock;
    @Autowired protected PaymentRepository paymentRepository;
    @Autowired protected PaymentOutboxRepository outboxRepository;
    @Autowired private IdempotencyKeyRepository idempotencyKeyRepository;
    @Autowired private RefundRepository refundRepository;
    @Autowired private com.awesome.payment.repository.OrderPaymentLockRepository orderPaymentLocks;

    @BeforeEach
    void resetBackend() {
        backend.reset();
        clock.reset();
    }

    @AfterEach
    void cleanDatabase() {
        refundRepository.deleteAll();
        outboxRepository.deleteAll();
        idempotencyKeyRepository.deleteAll();
        paymentRepository.deleteAll();
        orderPaymentLocks.deleteAll();
    }

    protected static String newKey() {
        return "test-key-" + KEYS.incrementAndGet();
    }

    protected static Map<String, Object> card(String number) {
        return Map.of("number", number, "expiryMonth", 12, "expiryYear", 2030, "cvc", "987",
                "holderName", "Jan Kowalski");
    }

    protected static Map<String, Object> payment(long orderId, String number) {
        return Map.of("orderId", orderId, "card", card(number));
    }

    /** POST /api/v1/payments with a fresh Idempotency-Key. */
    protected Response pay(long orderId, String number, String token) {
        return pay(newKey(), payment(orderId, number), token);
    }

    protected Response pay(String idempotencyKey, Object body, String token) {
        MockHttpServletRequestBuilder builder = post(PAYMENTS);
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        return perform(withBody(builder, body), token);
    }

    /** Pays a fresh PENDING order of "client" with {@code number} and returns the created payment. */
    protected PaymentDto paid(String number) {
        long orderId = backend.order("client", "126.00").id();
        Response response = pay(orderId, number, clientToken);
        if (response.status() != 201) {
            throw new IllegalStateException("Payment failed: " + response.status() + " " + response.body());
        }
        return response.as(PaymentDto.class);
    }

    protected Response threeDs(UUID paymentId, String code, String token) {
        return postJson(PAYMENTS + "/" + paymentId + "/3ds", Map.of("code", code), token);
    }

    protected Response refund(Object body, String serviceToken) {
        MockHttpServletRequestBuilder builder = withBody(post(REFUNDS), body);
        if (serviceToken != null) {
            builder.header("X-Service-Token", serviceToken);
        }
        return perform(builder, null);
    }

    protected Response getJson(String path, String token) {
        return perform(get(path), token);
    }

    protected Response postJson(String path, Object body, String token) {
        return perform(withBody(post(path), body), token);
    }

    private MockHttpServletRequestBuilder withBody(MockHttpServletRequestBuilder builder, Object body) {
        if (body == null) {
            return builder;
        }
        String json = body instanceof String raw ? raw : jsonMapper.writeValueAsString(body);
        return builder.contentType(MediaType.APPLICATION_JSON).content(json);
    }

    protected Response perform(MockHttpServletRequestBuilder builder, String token) {
        if (token != null) {
            builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        try {
            return new Response(mockMvc.perform(builder).andReturn(), jsonMapper);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Status plus a typed view of the JSON body. */
    public record Response(MvcResult result, JsonMapper mapper) {

        public int status() {
            return result.getResponse().getStatus();
        }

        public String header(String name) {
            return result.getResponse().getHeader(name);
        }

        public String body() {
            try {
                return result.getResponse().getContentAsString();
            } catch (java.io.UnsupportedEncodingException e) {
                throw new IllegalStateException(e);
            }
        }

        public <T> T as(Class<T> type) {
            return mapper.readValue(body(), type);
        }

        public <T> T as(TypeReference<T> type) {
            return mapper.readValue(body(), type);
        }

        public String message() {
            return mapper.readTree(body()).get("message").asString();
        }
    }
}
