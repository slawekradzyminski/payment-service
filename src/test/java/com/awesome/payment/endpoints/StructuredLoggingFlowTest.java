package com.awesome.payment.endpoints;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import com.awesome.payment.dto.payment.PaymentDto;
import com.awesome.payment.events.CardRefundRequestedHandler;
import com.awesome.payment.support.AbstractFlowTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * What {@code LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs} prints: Spring Boot's ECS encoder is attached
 * to the root logger for each test, so every line is one JSON object with the MDC correlation ids
 * as fields, and no line contains a card number or CVC.
 */
class StructuredLoggingFlowTest extends AbstractFlowTest {

    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final List<String> CARDS = List.of(VISA, MASTERCARD, DECLINED, INSUFFICIENT_FUNDS, THREE_DS,
            SETTLES, TIMES_OUT, DUPLICATE_NOTIFICATION, "4111 1111 1111 1111", "4111 1111 1111 1112",
            "5105105105105100");

    private final ByteArrayOutputStream json = new ByteArrayOutputStream();
    private OutputStreamAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachEcsAppender() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        StructuredLogEncoder encoder = new StructuredLogEncoder();
        encoder.setContext(context);
        encoder.setFormat("ecs");
        encoder.setCharset(StandardCharsets.UTF_8);
        encoder.start();
        appender = new OutputStreamAppender<>();
        appender.setContext(context);
        appender.setName("ecs-test");
        appender.setEncoder(encoder);
        appender.setOutputStream(json);
        appender.start();
        context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender);
    }

    @AfterEach
    void detachEcsAppender() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(appender);
        appender.stop();
    }

    @Test
    void everyLineIsJsonWithTheCorrelationIdsAsFields() {
        long orderId = backend.order("client", "126.00").id();
        Response created = perform(post(PAYMENTS).header("Idempotency-Key", newKey())
                .header("X-Request-Id", "json-req-1").header("traceparent", "00-" + TRACE + "-00f067aa0ba902b7-01")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(payment(orderId, VISA))), clientToken);
        PaymentDto payment = created.as(PaymentDto.class);
        String cancelTrace = "0af7651916cd43dd8448eb211c80319c";
        backend.publish(UUID.randomUUID(), CardRefundRequestedHandler.EVENT_TYPE, orderId,
                Map.of("requestId", UUID.randomUUID().toString(), "amount", "26.00"), "cancel-req-9", cancelTrace);
        poller.poll();

        List<JsonNode> lines = lines();

        assertThat(lines).isNotEmpty().allSatisfy(line -> {
            assertThat(line.get("@timestamp")).isNotNull();
            assertThat(line.get("message")).isNotNull();
            assertThat(line.at("/log/level").asString()).isNotBlank();
        });
        assertThat(lines).anySatisfy(line -> {
            assertThat(line.get("message").asString()).startsWith("Payment " + payment.id() + " for order " + orderId);
            assertThat(line.get("requestId").asString()).isEqualTo("json-req-1");
            assertThat(line.get("traceId").asString()).isEqualTo(TRACE);
            assertThat(line.get("spanId").asString()).matches("^[0-9a-f]{16}$");
        });
        assertThat(lines).anySatisfy(line -> {
            assertThat(line.get("message").asString()).startsWith("Refunded 26.00 of payment " + payment.id());
            assertThat(line.get("requestId").asString()).isEqualTo("cancel-req-9");
            assertThat(line.get("traceId").asString()).isEqualTo(cancelTrace);
        });
    }

    @Test
    void noJsonLineContainsACardNumberOrCvc() {
        for (String number : CARDS.subList(0, 8)) {
            pay(backend.order("client", "12.00").id(), number, clientToken);
        }
        Map<String, Object> expired = new HashMap<>(card("4111 1111 1111 1111"));
        expired.put("expiryYear", 2020);
        pay(newKey(), Map.of("orderId", backend.order("client", "1.00").id(), "card", expired), clientToken);
        pay(backend.order("client", "1.00").id(), "4111 1111 1111 1112", clientToken);
        pay(newKey(), "{\"orderId\":1,\"card\":{\"number\":\"5105105105105100\",\"expiryMonth\":\"x\"}}", clientToken);
        clock.advance(Duration.ofSeconds(10));
        backend.failNextResults(503);
        pay(backend.order("client", "5.00").id(), VISA, clientToken);

        String output = json.toString(StandardCharsets.UTF_8);
        assertThat(lines()).hasSizeGreaterThan(5);
        for (String number : CARDS) {
            String digits = number.replace(" ", "");
            assertThat(output).doesNotContain(digits, number, digits.substring(0, 12));
        }
        assertThat(output).doesNotContainIgnoringCase("cvc=987").doesNotContain("\"987\"", "\\\"987\\\"");
    }

    private List<JsonNode> lines() {
        return json.toString(StandardCharsets.UTF_8).lines().filter(line -> !line.isBlank())
                .map(jsonMapper::readTree).toList();
    }
}
