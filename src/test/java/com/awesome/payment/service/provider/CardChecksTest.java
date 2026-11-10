package com.awesome.payment.service.provider;

import com.awesome.payment.entity.CardBrand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class CardChecksTest {

    @ParameterizedTest
    @ValueSource(strings = {"4242424242424242", "5555555555554444", "4000000000000002", "4000000000009995",
        "4000000000003220", "4000000000000077", "4000000000000119", "4000000000000259", "378282246310005",
        "6011111111111117", "2223003122003222", "4111111111111111", "000000000000", "1234567890123452",
        "4012888888881881", "6011000990139424"})
    void testCardsAndKnownNumbersAreLuhnValid(String digits) {
        assertThat(CardChecks.isValidNumber(digits)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"4242424242424241", "4242424242424243", "5555555555554440", "4000000000000001",
        "1234567890123456", "4111111111111112", "378282246310006", "79927398710", "18"})
    void wrongCheckDigitsAreInvalid(String digits) {
        assertThat(CardChecks.isValidNumber(digits)).isFalse();
    }

    @Test
    void luhnDoublesEverySecondDigitFromTheRight() {
        assertThat(CardChecks.luhn("79927398713")).isTrue();
        assertThat(CardChecks.luhn("79927398710")).isFalse();
        assertThat(CardChecks.luhn("59")).isTrue();
        assertThat(CardChecks.luhn("91")).isTrue();
        assertThat(CardChecks.luhn("18")).isTrue();
        assertThat(CardChecks.luhn("81")).isFalse();
        assertThat(CardChecks.luhn("0")).isTrue();
        assertThat(CardChecks.luhn("5")).isFalse();
    }

    @Test
    void lengthMustBeTwelveToNineteenDigits() {
        assertThat(CardChecks.isValidNumber("00000000000")).isFalse();
        assertThat(CardChecks.isValidNumber("000000000000")).isTrue();
        assertThat(CardChecks.isValidNumber("0000000000000000000")).isTrue();
        assertThat(CardChecks.isValidNumber("00000000000000000000")).isFalse();
        assertThat(CardChecks.isValidNumber("0000000a00000")).isFalse();
        assertThat(CardChecks.isValidNumber("")).isFalse();
    }

    @Test
    void spacesAndDashesAreIgnored() {
        assertThat(CardChecks.digits("4242 4242-4242 4242")).isEqualTo("4242424242424242");
        assertThat(CardChecks.digits(null)).isEmpty();
        assertThat(CardChecks.last4("4242424242421234")).isEqualTo("1234");
    }

    @ParameterizedTest
    @CsvSource({
            "4242424242424242, VISA",
            "4000000000000002, VISA",
            "5100000000000000, MASTERCARD",
            "5555555555554444, MASTERCARD",
            "5000000000000000, OTHER",
            "5600000000000000, OTHER",
            "2221000000000000, MASTERCARD",
            "2720999999999999, MASTERCARD",
            "2220999999999999, OTHER",
            "2721000000000000, OTHER",
            "340000000000000, AMEX",
            "370000000000000, AMEX",
            "350000000000000, OTHER",
            "380000000000000, OTHER",
            "6011111111111117, OTHER"})
    void brandComesFromThePrefix(String digits, CardBrand brand) {
        assertThat(CardChecks.brand(digits)).isEqualTo(brand);
    }

    @Test
    void shortNumbersDoNotBreakBrandDetection() {
        assertThat(CardChecks.brand("5")).isEqualTo(CardBrand.OTHER);
        assertThat(CardChecks.brand("222")).isEqualTo(CardBrand.OTHER);
        assertThat(CardChecks.brand("55")).isEqualTo(CardBrand.MASTERCARD);
        assertThat(CardChecks.brand("2720")).isEqualTo(CardBrand.MASTERCARD);
    }
}
