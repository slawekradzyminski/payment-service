package com.awesome.payment.entity;

/** How the simulated provider resolves a PROCESSING payment once its settlement delay has passed. */
public enum Settlement {
    /** Settles to CAPTURED. */
    CAPTURE,
    /** Fails with PROVIDER_TIMEOUT. */
    TIMEOUT
}
