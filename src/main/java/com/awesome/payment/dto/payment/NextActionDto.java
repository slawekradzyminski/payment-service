package com.awesome.payment.dto.payment;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "What the customer must do before the payment can complete")
public record NextActionDto(
        @Schema(description = "Action type", example = THREE_DS_CHALLENGE) String type,
        @Schema(description = "Hint for the simulated challenge", example = "Enter code 123456 to approve") String hint) {

    public static final String THREE_DS_CHALLENGE = "THREE_DS_CHALLENGE";

    public static NextActionDto threeDsChallenge() {
        return new NextActionDto(THREE_DS_CHALLENGE, "Enter code 123456 to approve; any other code fails");
    }
}
