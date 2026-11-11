package com.awesome.payment.events;

import com.awesome.payment.client.FeedEvent;
import com.awesome.payment.service.RefundService;
import com.awesome.payment.service.RefundService.CardRefundRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CardRefundRequestedHandlerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID REQUEST_ID = UUID.fromString("00000000-0000-0000-0000-00000000a001");

    @Test
    void theContractPayloadBecomesARefundRequest() {
        RefundService refunds = mock(RefundService.class);
        CardRefundRequestedHandler handler = new CardRefundRequestedHandler(refunds);

        handler.handle(event(42L, """
                {"requestId":"00000000-0000-0000-0000-00000000a001","orderId":42,"amount":12.50,"reason":"Return #7"}"""));

        assertThat(handler.type()).isEqualTo("card-refund.requested");
        verify(refunds).refund(new CardRefundRequest(REQUEST_ID, 42L, new BigDecimal("12.50"), "Return #7"));
    }

    @Test
    void stringNumbersAndAMissingReasonAreAccepted() {
        CardRefundRequest request = CardRefundRequestedHandler.request(event(42L, """
                {"requestId":"00000000-0000-0000-0000-00000000a001","orderId":"43","amount":"5"}"""));

        assertThat(request).isEqualTo(new CardRefundRequest(REQUEST_ID, 43L, new BigDecimal("5.00"), null));
        assertThat(CardRefundRequestedHandler.request(event(42L, """
                {"requestId":"00000000-0000-0000-0000-00000000a001","orderId":44,"amount":1,"reason":null}""")).reason())
                .isNull();
    }

    @Test
    void theOrderIdFallsBackToTheEnvelope() {
        assertThat(CardRefundRequestedHandler.request(event(42L, """
                {"requestId":"00000000-0000-0000-0000-00000000a001","amount":1}""")).orderId()).isEqualTo(42L);
    }

    @Test
    void longReasonsAreTruncatedToTheColumn() {
        String reason = "x".repeat(300);
        CardRefundRequest request = CardRefundRequestedHandler.request(event(42L,
                "{\"requestId\":\"" + REQUEST_ID + "\",\"orderId\":42,\"amount\":1,\"reason\":\"" + reason + "\"}"));

        assertThat(request.reason()).hasSize(CardRefundRequestedHandler.MAX_REASON_LENGTH);
        assertThat(CardRefundRequestedHandler.request(event(42L, "{\"requestId\":\"" + REQUEST_ID
                + "\",\"orderId\":42,\"amount\":1,\"reason\":\"" + "y".repeat(255) + "\"}")).reason()).hasSize(255);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "null",
        "[]",
        "{\"orderId\":42,\"amount\":1}",
        "{\"requestId\":\"not-a-uuid\",\"orderId\":42,\"amount\":1}",
        "{\"requestId\":\"00000000-0000-0000-0000-00000000a001\",\"orderId\":42}",
        "{\"requestId\":\"00000000-0000-0000-0000-00000000a001\",\"orderId\":42,\"amount\":true}",
        "{\"requestId\":\"00000000-0000-0000-0000-00000000a001\",\"orderId\":42,\"amount\":0}",
        "{\"requestId\":\"00000000-0000-0000-0000-00000000a001\",\"orderId\":42,\"amount\":-3}",
        "{\"requestId\":\"00000000-0000-0000-0000-00000000a001\",\"orderId\":42,\"amount\":0.004}",
        "{\"requestId\":\"00000000-0000-0000-0000-00000000a001\",\"orderId\":42,\"amount\":null}"
    })
    void malformedPayloadsAreRejected(String data) {
        assertThatThrownBy(() -> CardRefundRequestedHandler.request(event(42L, data)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void noOrderIdAnywhereIsRejected() {
        assertThatThrownBy(() -> CardRefundRequestedHandler.request(event(null, """
                {"requestId":"00000000-0000-0000-0000-00000000a001","amount":1}""")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static FeedEvent event(Long orderId, String data) {
        return new FeedEvent(1, UUID.randomUUID(), "card-refund.requested", "2026-11-10T12:00:00Z", orderId, "client",
                "null".equals(data) ? null : JSON.readTree(data));
    }
}
