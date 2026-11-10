package com.awesome.payment.dto.payment;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "3-D Secure challenge answer")
public record ThreeDsDto(
        @Schema(description = "One-time code; 123456 approves, anything else fails", example = "123456")
        @NotBlank(message = "Code is required")
        @Size(max = 32, message = "Code must be at most 32 characters")
        String code) {
}
