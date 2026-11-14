package com.awesome.payment.controller;

import com.awesome.payment.dto.ErrorDto;
import com.awesome.payment.dto.ValidationErrorsDto;
import com.awesome.payment.dto.payment.CreatePaymentDto;
import com.awesome.payment.dto.payment.PaymentDto;
import com.awesome.payment.dto.payment.ThreeDsDto;
import com.awesome.payment.security.CustomPrincipal;
import com.awesome.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('CLIENT', 'ADMIN')")
@Tag(name = "Payments", description = "Simulated card payments for orders")
@SecurityRequirement(name = "bearerAuth")
@ApiResponse(responseCode = "401", description = "Unauthorized",
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
@ApiResponse(responseCode = "403", description = "Role not allowed, or paying another user's order",
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
public class PaymentController {

    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    public static final String REPLAYED = "Idempotent-Replayed";

    private final PaymentService paymentService;

    @PostMapping("/api/v1/payments")
    @Operation(summary = "Pay an order by card",
            description = "Charges the order's amount due (USD) to a test card. The outcome depends on the card "
                    + "number: 4242 4242 4242 4242 and 5555 5555 5555 4444 are captured, 4000 0000 0000 0002 is "
                    + "declined (CARD_DECLINED), 4000 0000 0000 9995 fails with INSUFFICIENT_FUNDS, "
                    + "4000 0000 0000 3220 requires 3-D Secure (code 123456), 4000 0000 0000 0077 is PROCESSING and "
                    + "settles to CAPTURED, 4000 0000 0000 0119 is PROCESSING and fails with PROVIDER_TIMEOUT, "
                    + "4000 0000 0000 0259 is captured and its result is delivered to the backend twice. Any other "
                    + "Luhn-valid number is captured. Repeating a request with the same Idempotency-Key and body "
                    + "returns the original payment with 200 and Idempotent-Replayed: true.")
    @ApiResponse(responseCode = "201", description = "Payment created (any status, including FAILED)")
    @ApiResponse(responseCode = "200", description = "Idempotent replay of an earlier request",
            headers = @Header(name = REPLAYED, description = "true", schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "400", description = "Invalid body, Idempotency-Key, card number (Luhn) or expired card",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(anyOf = {ValidationErrorsDto.class, ErrorDto.class})))
    @ApiResponse(responseCode = "404", description = "Order not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    @ApiResponse(responseCode = "409", description = "Order not payable (not PENDING, already paid, or another payment "
            + "in progress), \"Order reservation expired\" (the order's reservationExpiresAt has passed), or "
            + "Idempotency-Key reused with a different body",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    @ApiResponse(responseCode = "503", description = "Backend unavailable",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    public ResponseEntity<PaymentDto> create(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomPrincipal principal,
            @Parameter(description = "Unique per payment attempt, 8-64 characters of [A-Za-z0-9_-]", required = true)
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody CreatePaymentDto request) {
        PaymentService.CreateResult result = paymentService.create(principal, idempotencyKey, request);
        if (result.replayed()) {
            return ResponseEntity.ok().header(REPLAYED, "true").body(result.payment());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(result.payment());
    }

    @PostMapping("/api/v1/payments/{id}/3ds")
    @Operation(summary = "Answer the 3-D Secure challenge",
            description = "Completes a REQUIRES_ACTION payment: code 123456 captures it, any other code fails it "
                    + "with THREE_DS_FAILED.")
    @ApiResponse(responseCode = "200", description = "Challenge answered")
    @ApiResponse(responseCode = "400", description = "Missing code",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ValidationErrorsDto.class)))
    @ApiResponse(responseCode = "404", description = "Payment not found (or another user's payment)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    @ApiResponse(responseCode = "409", description = "Payment is not REQUIRES_ACTION",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    public PaymentDto threeDs(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody ThreeDsDto request) {
        return paymentService.completeThreeDs(principal, id, request.code());
    }

    @GetMapping("/api/v1/payments/{id}")
    @Operation(summary = "Get a payment", description = "Returns one of the caller's payments; admins see any payment. "
            + "Another user's payment is answered 404 like a missing one.")
    @ApiResponse(responseCode = "200", description = "Payment found")
    @ApiResponse(responseCode = "404", description = "Payment not found (or another user's payment)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    public PaymentDto get(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomPrincipal principal,
            @PathVariable UUID id) {
        return paymentService.get(principal, id);
    }

    @GetMapping("/api/v1/orders/{orderId}/payments")
    @Operation(summary = "List an order's payments",
            description = "Lists every payment attempt for the caller's order, newest first; admins see any order. "
                    + "Another user's order is answered 404 like a missing one.")
    @ApiResponse(responseCode = "200", description = "Payments listed",
            content = @Content(mediaType = "application/json",
                    array = @ArraySchema(schema = @Schema(implementation = PaymentDto.class))))
    @ApiResponse(responseCode = "404", description = "Order not found (or another user's order)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    @ApiResponse(responseCode = "503", description = "Backend unavailable",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    public List<PaymentDto> forOrder(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomPrincipal principal,
            @PathVariable Long orderId) {
        return paymentService.forOrder(principal, orderId);
    }
}
