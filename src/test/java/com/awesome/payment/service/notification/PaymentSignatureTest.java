package com.awesome.payment.service.notification;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentSignatureTest {

    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochSecond(1_700_000_000L), ZoneOffset.UTC);

    @Test
    void matchesAnIndependentlyComputedVector() {
        // printf '%s' '1700000000.{"a":1}' | openssl dgst -sha256 -hmac 'test-webhook-secret'
        assertThat(new PaymentSignature("test-webhook-secret", CLOCK).sign("{\"a\":1}"))
                .isEqualTo("t=1700000000,v1=c18e333538ce0ac3cd5677cc3fa0c44ce473c3e17a3861255ca2f1e96ac6b27e");
    }

    @Test
    void theTimestampAndEveryBodyByteAreCovered() {
        byte[] secret = "s".getBytes(StandardCharsets.UTF_8);
        String base = PaymentSignature.header(secret, 1, "{\"a\":1}");

        assertThat(PaymentSignature.header(secret, 2, "{\"a\":1}")).isNotEqualTo(base).startsWith("t=2,v1=");
        assertThat(PaymentSignature.header(secret, 1, "{\"a\":2}")).isNotEqualTo(base);
        assertThat(PaymentSignature.header("t".getBytes(StandardCharsets.UTF_8), 1, "{\"a\":1}")).isNotEqualTo(base);
        assertThat(base).matches("^t=1,v1=[0-9a-f]{64}$");
    }

    @Test
    void fingerprintsUseTheSameKey() {
        PaymentSignature signature = new PaymentSignature("test-webhook-secret", CLOCK);

        assertThat(signature.hmacHex("1700000000.{\"a\":1}"))
                .isEqualTo("c18e333538ce0ac3cd5677cc3fa0c44ce473c3e17a3861255ca2f1e96ac6b27e");
    }

    @Test
    void aSecretIsRequired() {
        for (String secret : new String[] {null, "", "  "}) {
            assertThatThrownBy(() -> new PaymentSignature(secret, CLOCK))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("PAYMENT_WEBHOOK_SECRET");
        }
    }
}
