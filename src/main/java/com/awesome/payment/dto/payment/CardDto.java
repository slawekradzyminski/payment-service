package com.awesome.payment.dto.payment;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Card details of a payment request. They are used once to decide the simulated outcome;
 * only the brand and last four digits are stored. {@link #toString()} masks the number and
 * the CVC so they cannot reach a log by accident.
 */
@Schema(description = "Card details; only brand and last four digits are stored")
public record CardDto(
        @Schema(description = "Card number, 12-19 digits; spaces and dashes are ignored", example = "4242 4242 4242 4242")
        @NotBlank(message = "Card number is required")
        @Pattern(regexp = "^[0-9][0-9 -]{10,36}[0-9]$", message = "Card number must contain 12-19 digits")
        String number,

        @Schema(description = "Expiry month, 1-12", example = "12")
        @NotNull(message = "Expiry month is required")
        @Min(value = 1, message = "Expiry month must be between 1 and 12")
        @Max(value = 12, message = "Expiry month must be between 1 and 12")
        Integer expiryMonth,

        @Schema(description = "Four-digit expiry year", example = "2030")
        @NotNull(message = "Expiry year is required")
        @Min(value = 2000, message = "Expiry year must be a four-digit year")
        @Max(value = 2099, message = "Expiry year must be a four-digit year")
        Integer expiryYear,

        @Schema(description = "Card verification code, 3-4 digits", example = "123")
        @NotBlank(message = "CVC is required")
        @Pattern(regexp = "^[0-9]{3,4}$", message = "CVC must be 3 or 4 digits")
        String cvc,

        @Schema(description = "Name on the card", example = "Jan Kowalski")
        @NotBlank(message = "Holder name is required")
        @Size(max = 100, message = "Holder name must be at most 100 characters")
        String holderName) {

    @Override
    public String toString() {
        return "CardDto[number=****, expiryMonth=" + expiryMonth + ", expiryYear=" + expiryYear
                + ", cvc=***, holderName=" + holderName + "]";
    }
}
