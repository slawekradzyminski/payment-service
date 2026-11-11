package com.awesome.payment.endpoints;

import com.awesome.payment.dto.PageDto;
import com.awesome.payment.dto.payment.PaymentDto;
import com.awesome.payment.entity.PaymentStatus;
import com.awesome.payment.support.AbstractFlowTest;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentQueryFlowTest extends AbstractFlowTest {

    @Test
    void ownerAndAdminSeeAPaymentOthersGetNotFound() {
        PaymentDto payment = paid(VISA);
        String path = PAYMENTS + "/" + payment.id();

        assertThat(getJson(path, clientToken).as(PaymentDto.class)).isEqualTo(payment);
        assertThat(getJson(path, adminToken).status()).isEqualTo(200);
        Response other = getJson(path, otherClientToken);
        Response missing = getJson(PAYMENTS + "/" + UUID.randomUUID(), clientToken);
        assertThat(other.status()).isEqualTo(404);
        assertThat(other.body()).isEqualTo(missing.body()).isEqualTo("{\"message\":\"Payment not found\"}");
        assertThat(missing.status()).isEqualTo(404);
        assertThat(getJson(path, null).status()).isEqualTo(401);
        assertThat(getJson(PAYMENTS + "/123", clientToken).status()).isEqualTo(400);
    }

    @Test
    void orderPaymentsAreListedNewestFirst() {
        long orderId = backend.order("client", "10.00").id();
        PaymentDto declined = pay(orderId, DECLINED, clientToken).as(PaymentDto.class);
        clock.advance(Duration.ofSeconds(1));
        PaymentDto failed3ds = pay(orderId, THREE_DS, clientToken).as(PaymentDto.class);
        threeDs(failed3ds.id(), "1", clientToken);
        clock.advance(Duration.ofSeconds(1));
        PaymentDto captured = pay(orderId, VISA, clientToken).as(PaymentDto.class);
        paid(VISA);

        Response response = getJson("/api/v1/orders/" + orderId + "/payments", clientToken);

        assertThat(response.status()).isEqualTo(200);
        List<PaymentDto> payments = response.as(new TypeReference<>() { });
        assertThat(payments).extracting(PaymentDto::id).containsExactly(captured.id(), failed3ds.id(), declined.id());
        assertThat(getJson("/api/v1/orders/" + orderId + "/payments", adminToken).as(new TypeReference<List<PaymentDto>>() { }))
                .hasSize(3);
    }

    @Test
    void orderPaymentsNeedAnExistingOwnOrder() {
        long orderId = backend.order("client", "10.00").id();
        paid(VISA);
        pay(orderId, VISA, clientToken);

        assertThat(getJson("/api/v1/orders/" + orderId + "/payments", clientToken).status()).isEqualTo(200);
        assertThat(getJson("/api/v1/orders/" + orderId + "/payments", adminToken).status()).isEqualTo(200);
        // Another user's order is answered exactly like a missing one (as the backend does).
        Response other = getJson("/api/v1/orders/" + orderId + "/payments", otherClientToken);
        Response unknown = getJson("/api/v1/orders/987654/payments", clientToken);
        assertThat(other.status()).isEqualTo(404);
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(other.body()).isEqualTo(unknown.body()).isEqualTo("{\"message\":\"Order not found\"}");
        backend.setAvailable(false);
        assertThat(getJson("/api/v1/orders/" + orderId + "/payments", clientToken).status()).isEqualTo(503);
        backend.setAvailable(true);
        assertThat(getJson("/api/v1/orders/" + backend.order("other", "1.00").id() + "/payments", clientToken).body())
                .isEqualTo("{\"message\":\"Order not found\"}");
    }

    @Test
    void adminListsAllPaymentsNewestFirstWithStatusFilterAndPaging() {
        PaymentDto first = paid(VISA);
        clock.advance(Duration.ofSeconds(1));
        PaymentDto second = paid(DECLINED);
        clock.advance(Duration.ofSeconds(1));
        PaymentDto third = pay(backend.order("other", "5.00").id(), VISA, otherClientToken).as(PaymentDto.class);

        PageDto<PaymentDto> all = admin("/api/v1/admin/payments");
        assertThat(all.getContent()).extracting(PaymentDto::id).containsExactly(third.id(), second.id(), first.id());
        assertThat(all.getTotalElements()).isEqualTo(3);
        assertThat(all.getPageSize()).isEqualTo(20);

        PageDto<PaymentDto> captured = admin("/api/v1/admin/payments?status=CAPTURED");
        assertThat(captured.getContent()).extracting(PaymentDto::status).containsOnly(PaymentStatus.CAPTURED);
        assertThat(captured.getTotalElements()).isEqualTo(2);

        PageDto<PaymentDto> page = admin("/api/v1/admin/payments?page=1&size=2");
        assertThat(page.getContent()).extracting(PaymentDto::id).containsExactly(first.id());
        assertThat(page.getPageNumber()).isEqualTo(1);
        assertThat(page.getTotalPages()).isEqualTo(2);

        PageDto<PaymentDto> clamped = admin("/api/v1/admin/payments?page=-3&size=500");
        assertThat(clamped.getPageNumber()).isZero();
        assertThat(clamped.getPageSize()).isEqualTo(100);
        assertThat(admin("/api/v1/admin/payments?size=0").getPageSize()).isEqualTo(1);
    }

    @Test
    void adminListIsAdminOnlyAndRejectsUnknownStatus() {
        assertThat(getJson("/api/v1/admin/payments", clientToken).status()).isEqualTo(403);
        assertThat(getJson("/api/v1/admin/payments", null).status()).isEqualTo(401);
        Response bad = getJson("/api/v1/admin/payments?status=LOST", adminToken);
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.message()).isEqualTo("Invalid value for status");
    }

    private PageDto<PaymentDto> admin(String path) {
        Response response = getJson(path, adminToken);
        assertThat(response.status()).isEqualTo(200);
        return response.as(new TypeReference<>() { });
    }
}
