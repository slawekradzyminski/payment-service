package com.awesome.payment.service.provider;

import com.awesome.payment.controller.exception.CustomException;
import com.awesome.payment.dto.payment.CardDto;
import com.awesome.payment.service.provider.ProviderDecision.Outcome;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.YearMonth;
import java.util.Map;

/**
 * Simulated card provider. The outcome depends only on the card number (see the test-card
 * table in the contract); any other Luhn-valid number is captured. The number and CVC are
 * never stored or logged: callers keep only {@link ProviderDecision#brand()} and
 * {@link ProviderDecision#last4()}.
 */
@Component
@RequiredArgsConstructor
public class FakeCardProvider {

    public static final String THREE_DS_CODE = "123456";
    public static final String CARD_DECLINED = "CARD_DECLINED";
    public static final String INSUFFICIENT_FUNDS = "INSUFFICIENT_FUNDS";
    public static final String THREE_DS_FAILED = "THREE_DS_FAILED";
    public static final String PROVIDER_TIMEOUT = "PROVIDER_TIMEOUT";
    public static final String THREE_DS_EXPIRED = "THREE_DS_EXPIRED";

    static final String INVALID_NUMBER = "Card number is invalid";
    static final String EXPIRED = "Card has expired";

    private static final Map<String, Behaviour> TEST_CARDS = Map.of(
            "4000000000000002", new Behaviour(Outcome.FAILED, CARD_DECLINED, false),
            "4000000000009995", new Behaviour(Outcome.FAILED, INSUFFICIENT_FUNDS, false),
            "4000000000003220", new Behaviour(Outcome.REQUIRES_ACTION, null, false),
            "4000000000000077", new Behaviour(Outcome.SETTLES, null, false),
            "4000000000000119", new Behaviour(Outcome.TIMES_OUT, null, false),
            "4000000000000259", new Behaviour(Outcome.CAPTURED, null, true));
    private static final Behaviour CAPTURE = new Behaviour(Outcome.CAPTURED, null, false);

    private final Clock paymentClock;

    /**
     * Checks the card and decides the simulated outcome.
     *
     * @throws CustomException 400 for a Luhn-invalid number or an expired card
     */
    public ProviderDecision authorize(CardDto card) {
        String digits = CardChecks.digits(card.number());
        if (!CardChecks.isValidNumber(digits)) {
            throw new CustomException(INVALID_NUMBER, HttpStatus.BAD_REQUEST);
        }
        if (isExpired(card.expiryMonth(), card.expiryYear())) {
            throw new CustomException(EXPIRED, HttpStatus.BAD_REQUEST);
        }
        Behaviour behaviour = TEST_CARDS.getOrDefault(digits, CAPTURE);
        return new ProviderDecision(CardChecks.brand(digits), CardChecks.last4(digits), behaviour.outcome(),
                behaviour.failureCode(), behaviour.notifyTwice());
    }

    /** A card is valid through the last day of its expiry month. */
    boolean isExpired(int month, int year) {
        return YearMonth.of(year, month).isBefore(YearMonth.now(paymentClock));
    }

    /** Whether a 3-D Secure answer approves the payment. */
    public boolean threeDsApproved(String code) {
        return THREE_DS_CODE.equals(code);
    }

    /** Customer-facing text for a failure code. */
    public static String failureMessage(String failureCode) {
        return switch (failureCode) {
            case CARD_DECLINED -> "Your card was declined";
            case INSUFFICIENT_FUNDS -> "Your card has insufficient funds";
            case THREE_DS_FAILED -> "3-D Secure authentication failed";
            case PROVIDER_TIMEOUT -> "The payment provider did not respond in time";
            case THREE_DS_EXPIRED -> "3-D Secure was not completed in time";
            default -> "The payment failed";
        };
    }

    private record Behaviour(Outcome outcome, String failureCode, boolean notifyTwice) {
    }
}
