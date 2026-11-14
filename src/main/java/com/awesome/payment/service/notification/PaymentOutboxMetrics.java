package com.awesome.payment.service.notification;

import com.awesome.payment.repository.PaymentOutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * The result outbox's metrics, named by the outbox convention (ADR 3) and scraped by Prometheus
 * from {@code /actuator/prometheus}:
 * <ul>
 *   <li>{@value #PENDING} (gauge, {@code payment_outbox_pending}): rows not sent and not given up,
 *       counted in the database on every scrape, so it survives restarts and covers every instance;</li>
 *   <li>{@value #FAILED} (counter, {@code payment_outbox_failed_total}): rows this instance gave up
 *       on (a final rejection, or the last of {@value PaymentResultDispatcher#MAX_ATTEMPTS} attempts).
 *       A 409 that voids the capture is handled, not given up, and is not counted.</li>
 * </ul>
 */
@Component
public class PaymentOutboxMetrics {

    public static final String PENDING = "payment.outbox.pending";
    public static final String FAILED = "payment.outbox.failed";

    private final Counter failed;

    public PaymentOutboxMetrics(MeterRegistry registry, PaymentOutboxRepository outboxRepository) {
        Gauge.builder(PENDING, outboxRepository, repository -> repository.countPending(
                        PaymentResultDispatcher.MAX_ATTEMPTS))
                .description("Payment results not yet delivered to the backend and not given up")
                .strongReference(true)
                .register(registry);
        this.failed = Counter.builder(FAILED)
                .description("Payment results given up on (final rejection or attempts exhausted)")
                .register(registry);
    }

    /** One more row given up. */
    void givenUp() {
        failed.increment();
    }
}
