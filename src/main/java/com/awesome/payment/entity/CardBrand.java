package com.awesome.payment.entity;

/** Card network derived from the card number prefix; the only card detail besides the last four digits that is stored. */
public enum CardBrand {
    VISA,
    MASTERCARD,
    AMEX,
    OTHER
}
