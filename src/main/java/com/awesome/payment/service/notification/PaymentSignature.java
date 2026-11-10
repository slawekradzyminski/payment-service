package com.awesome.payment.service.notification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.util.HexFormat;

/**
 * Signs result notifications: {@code X-Payment-Signature: t=<epochSeconds>,v1=<hex>} where
 * {@code hex = HMAC-SHA256(PAYMENT_WEBHOOK_SECRET, t + "." + rawBody)}. The backend rejects
 * timestamps more than five minutes away, so every delivery attempt is signed afresh.
 */
@Component
public class PaymentSignature {

    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] secret;
    private final Clock paymentClock;

    public PaymentSignature(@Value("${payment.webhook-secret:}") String webhookSecret, Clock paymentClock) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new IllegalStateException("payment.webhook-secret (PAYMENT_WEBHOOK_SECRET) must be configured");
        }
        this.secret = webhookSecret.getBytes(StandardCharsets.UTF_8);
        this.paymentClock = paymentClock;
    }

    /** The header value for {@code rawBody}, timestamped now. */
    public String sign(String rawBody) {
        return header(secret, paymentClock.instant().getEpochSecond(), rawBody);
    }

    /** Hex HMAC-SHA256 of {@code data} with the webhook secret (used for request fingerprints too). */
    public String hmacHex(String data) {
        return hmacHex(secret, data);
    }

    static String header(byte[] secret, long epochSeconds, String rawBody) {
        return "t=" + epochSeconds + ",v1=" + hmacHex(secret, epochSeconds + "." + rawBody);
    }

    static String hmacHex(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }
}
