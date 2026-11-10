package com.awesome.payment.service.provider;

import com.awesome.payment.entity.CardBrand;

/** Card-number arithmetic: normalisation, the Luhn checksum and brand detection. */
public final class CardChecks {

    static final int MIN_DIGITS = 12;
    static final int MAX_DIGITS = 19;
    private static final int MAX_DIGIT = 9;

    private CardChecks() {
    }

    /** The digits of a number typed with optional spaces or dashes. */
    public static String digits(String number) {
        return number == null ? "" : number.replaceAll("[ -]", "");
    }

    /** True for 12-19 digits whose Luhn checksum is valid. */
    public static boolean isValidNumber(String digits) {
        if (digits.length() < MIN_DIGITS || digits.length() > MAX_DIGITS || !digits.chars().allMatch(Character::isDigit)) {
            return false;
        }
        return luhn(digits);
    }

    static boolean luhn(String digits) {
        int sum = 0;
        boolean doubled = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int digit = digits.charAt(i) - '0';
            if (doubled) {
                digit *= 2;
                if (digit > MAX_DIGIT) {
                    digit -= MAX_DIGIT;
                }
            }
            sum += digit;
            doubled = !doubled;
        }
        return sum % 10 == 0;
    }

    /** VISA (4), MASTERCARD (51-55, 2221-2720), AMEX (34, 37), otherwise OTHER. */
    public static CardBrand brand(String digits) {
        if (digits.startsWith("4")) {
            return CardBrand.VISA;
        }
        if (digits.startsWith("34") || digits.startsWith("37")) {
            return CardBrand.AMEX;
        }
        int two = prefix(digits, 2);
        int four = prefix(digits, 4);
        if ((two >= 51 && two <= 55) || (four >= 2221 && four <= 2720)) {
            return CardBrand.MASTERCARD;
        }
        return CardBrand.OTHER;
    }

    public static String last4(String digits) {
        return digits.substring(digits.length() - 4);
    }

    private static int prefix(String digits, int length) {
        return digits.length() < length ? -1 : Integer.parseInt(digits.substring(0, length));
    }
}
