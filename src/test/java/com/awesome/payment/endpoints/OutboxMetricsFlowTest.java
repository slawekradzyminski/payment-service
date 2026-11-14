package com.awesome.payment.endpoints;

import com.awesome.payment.service.notification.PaymentResultDispatcher;
import com.awesome.payment.support.AbstractFlowTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The result outbox metrics of the outbox convention (ADR 3), as Prometheus scrapes them:
 * {@code payment_outbox_pending} (gauge) and {@code payment_outbox_failed_total} (counter).
 */
class OutboxMetricsFlowTest extends AbstractFlowTest {

    @Autowired
    private PaymentResultDispatcher dispatcher;

    @Test
    void anUndeliveredResultIsPendingUntilItIsSent() {
        assertThat(metric("payment_outbox_pending")).isZero();
        backend.failNextResults(503);

        paid(VISA);

        assertThat(metric("payment_outbox_pending")).isEqualTo(1.0);
        clock.advance(Duration.ofSeconds(31));
        assertThat(dispatcher.dispatchDue()).isEqualTo(1);
        assertThat(metric("payment_outbox_pending")).isZero();
    }

    @Test
    void aResultGivenUpIsCountedAndNoLongerPending() {
        double failedBefore = metric("payment_outbox_failed_total");
        backend.failNextResults(400);

        paid(VISA);

        assertThat(metric("payment_outbox_failed_total")).isEqualTo(failedBefore + 1);
        assertThat(metric("payment_outbox_pending")).isZero();
    }

    private double metric(String name) {
        Response scrape = getJson("/actuator/prometheus", null);
        assertThat(scrape.status()).isEqualTo(200);
        Matcher matcher = Pattern.compile("(?m)^" + name + "(\\{[^}]*})? ([0-9.Ee+-]+)$").matcher(scrape.body());
        assertThat(matcher.find()).as(name + " in the scrape").isTrue();
        return Double.parseDouble(matcher.group(2));
    }
}
