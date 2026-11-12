package com.awesome.payment.endpoints;

import com.awesome.payment.dto.payment.PaymentDto;
import com.awesome.payment.entity.PaymentStatus;
import com.awesome.payment.support.AbstractFlowTest;
import com.awesome.payment.support.TestTokens;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.time.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrent submissions. For one order, the fake backend's order lookup is slowed down so that,
 * without the per-order lock, both requests would pass the "no active payment" check. Across
 * orders, many first payments at once must not starve the production-sized connection pool.
 */
class ConcurrentPaymentFlowTest extends AbstractFlowTest {

    private static final int PRODUCTION_POOL_SIZE = 3;
    private static final int CONCURRENT_PAYMENTS = 12;

    @Autowired
    private DataSource dataSource;

    /**
     * Regression for 1.2.0, where a first payment held its connection while taking a second one
     * for the lock row (REQUIRES_NEW) and the after-commit result delivery did the same on the
     * request thread around the backend call: a handful of simultaneous payments exhausted the
     * pool of three and every request hung until Hikari gave up (500 after 30 s).
     */
    @Test
    void manyFirstPaymentsAtOnceDoNotStarveTheConnectionPool() throws Exception {
        assertThat(dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize()).isEqualTo(PRODUCTION_POOL_SIZE);
        backend.setResultDelayMillis(50);
        List<Callable<Response>> calls = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_PAYMENTS; i++) {
            String buyer = "buyer-" + i;
            long orderId = backend.order(buyer, "30.00").id();
            String card = i == 0 ? DUPLICATE_NOTIFICATION : VISA;
            String token = TestTokens.token(buyer, "ROLE_CLIENT");
            calls.add(() -> pay(orderId, card, token));
        }

        long started = System.nanoTime();
        List<Response> responses = concurrently(Duration.ofSeconds(20), calls);

        assertThat(responses).extracting(Response::status).containsOnly(201);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(15));
        assertThat(paymentRepository.count()).isEqualTo(CONCURRENT_PAYMENTS);
        awaitResultDelivery();
        assertThat(backend.deliveries()).hasSize(CONCURRENT_PAYMENTS + 1);
        assertThat(outboxRepository.findAll()).allSatisfy(row -> assertThat(row.getSentAt()).isNotNull());
    }

    @Test
    void noConnectionIsHeldWhileTheBackendIsAskedForTheOrder() throws Exception {
        HikariPoolMXBean pool = dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean();
        List<Integer> activeDuringLookup = new CopyOnWriteArrayList<>();
        backend.setOrderLookupProbe(() -> activeDuringLookup.add(pool.getActiveConnections()));
        long orderId = backend.order("client", "30.00").id();

        assertThat(pay(orderId, VISA, clientToken).status()).isEqualTo(201);
        assertThat(getJson("/api/v1/orders/" + orderId + "/payments", clientToken).status()).isEqualTo(200);

        // the create, the result delivery (the fake backend reads the order) and the order's payments
        assertThat(activeDuringLookup).hasSizeGreaterThanOrEqualTo(3).containsOnly(0);
    }

    @Test
    void aDoubleSubmitWithTheSameKeyCreatesOnePaymentAndReplaysIt() throws Exception {
        long orderId = backend.order("client", "30.00").id();
        String key = newKey();
        backend.setOrderLookupDelayMillis(300);

        List<Response> responses = concurrently(() -> pay(key, payment(orderId, VISA), clientToken),
                () -> pay(key, payment(orderId, VISA), clientToken));

        assertThat(responses).extracting(Response::status).containsExactlyInAnyOrder(201, 200);
        assertThat(responses.get(0).as(PaymentDto.class).id()).isEqualTo(responses.get(1).as(PaymentDto.class).id());
        assertThat(paymentRepository.count()).isEqualTo(1);
        assertThat(backend.deliveries()).hasSize(1);
    }

    @Test
    void twoDifferentAttemptsCannotBothBecomeActive() throws Exception {
        long orderId = backend.order("client", "30.00").id();
        backend.setOrderLookupDelayMillis(300);

        List<Response> responses = concurrently(() -> pay(orderId, THREE_DS, clientToken),
                () -> pay(orderId, SETTLES, clientToken));

        assertThat(responses).extracting(Response::status).containsExactlyInAnyOrder(201, 409);
        assertThat(paymentRepository.findByOrderIdOrderByCreatedAtDescIdDesc(orderId))
                .singleElement()
                .satisfies(p -> assertThat(p.getStatus()).isIn(PaymentStatus.REQUIRES_ACTION, PaymentStatus.PROCESSING));
    }

    @SafeVarargs
    private List<Response> concurrently(Callable<Response>... calls) throws Exception {
        return concurrently(Duration.ofSeconds(30), List.of(calls));
    }

    /** Starts every call at the same moment; fails when they have not all answered within {@code timeout}. */
    private List<Response> concurrently(Duration timeout, List<Callable<Response>> calls) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(calls.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Response>> futures = new ArrayList<>();
            for (Callable<Response> call : calls) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            long deadline = System.nanoTime() + timeout.toNanos();
            List<Response> responses = new ArrayList<>();
            for (Future<Response> future : futures) {
                responses.add(future.get(Math.max(deadline - System.nanoTime(), 0), TimeUnit.NANOSECONDS));
            }
            return responses;
        } finally {
            executor.shutdownNow();
        }
    }
}
