package com.awesome.payment.controller;

import com.awesome.payment.dto.ErrorDto;
import com.awesome.payment.dto.PageDto;
import com.awesome.payment.dto.payment.PaymentDto;
import com.awesome.payment.entity.PaymentStatus;
import com.awesome.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Payments (admin)", description = "All payments")
@SecurityRequirement(name = "bearerAuth")
@ApiResponse(responseCode = "401", description = "Unauthorized",
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
@ApiResponse(responseCode = "403", description = "Admin role required",
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
public class AdminPaymentController {

    private final PaymentService paymentService;

    @GetMapping("/api/v1/admin/payments")
    @Operation(summary = "List all payments",
            description = "Lists payments of every customer newest first, optionally filtered by status. Negative page "
                    + "is clamped to zero; size is clamped to 1-100.")
    @ApiResponse(responseCode = "200", description = "Payments listed")
    @ApiResponse(responseCode = "400", description = "Unknown status",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    public PageDto<PaymentDto> all(
            @Parameter(description = "Only payments in this status") @RequestParam(required = false) PaymentStatus status,
            @Parameter(description = "Zero-based page index") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, 1-100") @RequestParam(defaultValue = "20") int size) {
        return PageDto.from(paymentService.all(status, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")))));
    }
}
