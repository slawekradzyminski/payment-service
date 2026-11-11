package com.awesome.payment.endpoints;

import com.awesome.payment.dto.payment.CardDto;
import com.awesome.payment.support.AbstractFlowTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Card numbers and CVCs must never reach the logs or the database. */
@ExtendWith(OutputCaptureExtension.class)
class CardDataLeakTest extends AbstractFlowTest {

    private static final List<String> TABLES = List.of("payments", "payment_idempotency_keys", "payment_outbox",
            "refunds", "processed_events", "event_cursors");

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void neitherLogsNorTablesContainCardNumbersOrCvc(CapturedOutput output) {
        List<String> numbers = List.of(VISA, MASTERCARD, DECLINED, THREE_DS, SETTLES, DUPLICATE_NOTIFICATION);
        for (String number : numbers) {
            long orderId = backend.order("client", "12.00").id();
            pay(orderId, number, clientToken);
        }
        Map<String, Object> expired = new HashMap<>(card("4111 1111 1111 1111"));
        expired.put("expiryYear", 2020);
        pay(newKey(), Map.of("orderId", backend.order("client", "1.00").id(), "card", expired), clientToken);
        pay(backend.order("client", "1.00").id(), "4111 1111 1111 1112", clientToken);
        pay(newKey(), "{\"orderId\":1,\"card\":{\"number\":\"5105105105105100\",\"expiryMonth\":\"x\"}}", clientToken);
        clock.advance(Duration.ofSeconds(10));

        StringBuilder database = new StringBuilder();
        for (String table : TABLES) {
            jdbc.queryForList("select * from payment." + table).forEach(row -> database.append(row.values()).append('\n'));
        }
        assertThat(database).contains("4242", "4444");
        for (String number : List.of(VISA, MASTERCARD, DECLINED, THREE_DS, SETTLES, DUPLICATE_NOTIFICATION,
                "4111 1111 1111 1111", "4111 1111 1111 1112", "5105105105105100")) {
            String digits = number.replace(" ", "");
            assertThat(output.getAll()).doesNotContain(digits, number, digits.substring(0, 12));
            assertThat(database).doesNotContain(digits, number, digits.substring(0, 12));
        }
        assertThat(output.getAll()).doesNotContainIgnoringCase("cvc=987").doesNotContain("\"987\"");
    }

    @Test
    void theCardDtoMasksNumberAndCvcInToString() {
        CardDto card = new CardDto("4242 4242 4242 4242", 12, 2030, "987", "Jan Kowalski");

        assertThat(card.toString()).doesNotContain("4242", "987").contains("number=****", "cvc=***", "Jan Kowalski");
    }
}
