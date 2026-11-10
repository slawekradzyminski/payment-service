package com.awesome.payment.dto.payment;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@Schema(description = "Pays a PENDING order by card")
public record CreatePaymentDto(
        @Schema(description = "Backend order id", example = "42")
        @NotNull(message = "Order id is required")
        @Positive(message = "Order id must be positive")
        Long orderId,

        @NotNull(message = "Card is required")
        @Valid
        CardDto card) {
}
