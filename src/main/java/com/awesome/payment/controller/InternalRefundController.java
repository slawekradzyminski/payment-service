package com.awesome.payment.controller;

import com.awesome.payment.dto.ErrorDto;
import com.awesome.payment.dto.ValidationErrorsDto;
import com.awesome.payment.dto.refund.RefundRequestDto;
import com.awesome.payment.dto.refund.RefundResponseDto;
import com.awesome.payment.service.RefundService;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Backend-only API, guarded by {@code X-Service-Token} and left out of the OpenAPI document. */
@Hidden
@RestController
@RequiredArgsConstructor
public class InternalRefundController {

    private final RefundService refundService;

    @PostMapping("/internal/v1/payments/refunds")
    @Operation(summary = "Refund to the card",
            description = "Books a card refund for the order's captured payment; idempotent per requestId. Called by "
                    + "the backend with X-Service-Token.")
    @ApiResponse(responseCode = "200", description = "Refund booked (or replayed)")
    @ApiResponse(responseCode = "400", description = "Invalid body",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ValidationErrorsDto.class)))
    @ApiResponse(responseCode = "401", description = "Missing or wrong service token",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    @ApiResponse(responseCode = "404", description = "No captured payment for the order, or service token unset",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    @ApiResponse(responseCode = "409", description = "Amount exceeds what is still refundable",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    public RefundResponseDto refund(@Valid @RequestBody RefundRequestDto request) {
        return refundService.refund(request);
    }
}
