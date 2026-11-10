package com.awesome.payment.service.provider;

import com.awesome.payment.entity.CardBrand;

/**
 * The simulated provider's answer for one card: what is stored about the card and how the
 * payment proceeds.
 *
 * @param failureCode     set when {@code outcome} is {@link Outcome#FAILED}
 * @param notifyTwice     the result notification is delivered twice with the same requestId
 */
public record ProviderDecision(CardBrand brand, String last4, Outcome outcome, String failureCode, boolean notifyTwice) {

    public enum Outcome {
        /** Captured immediately. */
        CAPTURED,
        /** Declined immediately with {@code failureCode}. */
        FAILED,
        /** Waits for a 3-D Secure challenge. */
        REQUIRES_ACTION,
        /** PROCESSING, captured when the settlement delay has passed. */
        SETTLES,
        /** PROCESSING, fails with PROVIDER_TIMEOUT when the settlement delay has passed. */
        TIMES_OUT
    }
}
