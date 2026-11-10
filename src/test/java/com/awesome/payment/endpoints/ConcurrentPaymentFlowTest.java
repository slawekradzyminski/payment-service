package com.awesome.payment.endpoints;

import com.awesome.payment.dto.payment.PaymentDto;
import com.awesome.payment.entity.PaymentStatus;
import com.awesome.payment.support.AbstractFlowTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrent submissions for one order. The fake backend's order lookup is slowed down so that,
 * without the per-order lock, both requests would pass the "no active payment" check.
 */
class ConcurrentPaymentFlowTest extends AbstractFlowTest {

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
        ExecutorService executor = Executors.newFixedThreadPool(calls.length);
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
            List<Response> responses = new ArrayList<>();
            for (Future<Response> future : futures) {
                responses.add(future.get());
            }
            return responses;
        } finally {
            executor.shutdownNow();
        }
    }
}
