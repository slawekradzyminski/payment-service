package com.awesome.payment.service.provider;

import com.awesome.payment.controller.exception.CustomException;
import com.awesome.payment.dto.payment.CardDto;
import com.awesome.payment.entity.CardBrand;
import com.awesome.payment.service.provider.ProviderDecision.Outcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FakeCardProviderTest {

    private final FakeCardProvider provider = new FakeCardProvider(
            Clock.fixed(Instant.parse("2026-11-30T23:59:59Z"), ZoneOffset.UTC));

    @ParameterizedTest
    @CsvSource(nullValues = "-", value = {
            "4242 4242 4242 4242, VISA, 4242, CAPTURED, -, false",
            "5555 5555 5555 4444, MASTERCARD, 4444, CAPTURED, -, false",
            "4000 0000 0000 0002, VISA, 0002, FAILED, CARD_DECLINED, false",
            "4000 0000 0000 9995, VISA, 9995, FAILED, INSUFFICIENT_FUNDS, false",
            "4000 0000 0000 3220, VISA, 3220, REQUIRES_ACTION, -, false",
            "4000 0000 0000 0077, VISA, 0077, SETTLES, -, false",
            "4000 0000 0000 0119, VISA, 0119, TIMES_OUT, -, false",
            "4000 0000 0000 0259, VISA, 0259, CAPTURED, -, true",
            "3782 822463 10005, AMEX, 0005, CAPTURED, -, false"})
    void outcomeFollowsTheTestCardTable(String number, CardBrand brand, String last4, Outcome outcome,
                                        String failureCode, boolean notifyTwice) {
        ProviderDecision decision = provider.authorize(card(number, 12, 2030));

        assertThat(decision).isEqualTo(new ProviderDecision(brand, last4, outcome, failureCode, notifyTwice));
    }

    @Test
    void luhnInvalidNumbersAreRejected() {
        assertRejected(card("4242 4242 4242 4241", 12, 2030), "Card number is invalid");
        assertRejected(card("4242 4242 424", 12, 2030), "Card number is invalid");
    }

    @Test
    void cardsAreValidThroughTheEndOfTheExpiryMonth() {
        assertThat(provider.authorize(card("4242424242424242", 11, 2026)).outcome()).isEqualTo(Outcome.CAPTURED);
        assertThat(provider.authorize(card("4242424242424242", 1, 2027)).outcome()).isEqualTo(Outcome.CAPTURED);
        assertRejected(card("4242424242424242", 10, 2026), "Card has expired");
        assertRejected(card("4242424242424242", 12, 2025), "Card has expired");
    }

    @Test
    void theLuhnCheckComesBeforeTheExpiryCheck() {
        assertRejected(card("4242424242424241", 1, 2020), "Card number is invalid");
    }

    @Test
    void onlyTheDocumentedCodePassesThreeDs() {
        assertThat(provider.threeDsApproved("123456")).isTrue();
        assertThat(provider.threeDsApproved("123457")).isFalse();
        assertThat(provider.threeDsApproved(" 123456")).isFalse();
        assertThat(provider.threeDsApproved(null)).isFalse();
    }

    @Test
    void everyFailureCodeHasAMessage() {
        assertThat(FakeCardProvider.failureMessage("CARD_DECLINED")).isEqualTo("Your card was declined");
        assertThat(FakeCardProvider.failureMessage("INSUFFICIENT_FUNDS")).isEqualTo("Your card has insufficient funds");
        assertThat(FakeCardProvider.failureMessage("THREE_DS_FAILED")).isEqualTo("3-D Secure authentication failed");
        assertThat(FakeCardProvider.failureMessage("PROVIDER_TIMEOUT"))
                .isEqualTo("The payment provider did not respond in time");
        assertThat(FakeCardProvider.failureMessage("SOMETHING_ELSE")).isEqualTo("The payment failed");
    }

    private void assertRejected(CardDto card, String message) {
        assertThatThrownBy(() -> provider.authorize(card)).isInstanceOfSatisfying(CustomException.class, e -> {
            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.getMessage()).isEqualTo(message);
        });
    }

    private static CardDto card(String number, int month, int year) {
        return new CardDto(number, month, year, "987", "Jan Kowalski");
    }
}
