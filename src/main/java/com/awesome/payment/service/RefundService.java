package com.awesome.payment.service;

import com.awesome.payment.controller.exception.CustomException;
import com.awesome.payment.dto.refund.RefundRequestDto;
import com.awesome.payment.dto.refund.RefundResponseDto;
import com.awesome.payment.entity.PaymentEntity;
import com.awesome.payment.entity.PaymentStatus;
import com.awesome.payment.entity.RefundEntity;
import com.awesome.payment.repository.PaymentRepository;
import com.awesome.payment.repository.RefundRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Card refunds booked by the backend. Idempotent per requestId: a repeated request gets the
 * first answer. 404 when the order has no captured card payment, 409 above the refundable rest.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundService {

    static final String NO_CAPTURED_PAYMENT = "No captured payment for this order";

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final Clock paymentClock;

    @Transactional
    public RefundResponseDto refund(RefundRequestDto request) {
        Optional<RefundEntity> earlier = refundRepository.findByRequestId(request.requestId());
        if (earlier.isPresent()) {
            return RefundResponseDto.from(earlier.get());
        }
        PaymentEntity payment = paymentRepository.findByOrderIdAndStatusInOrderByCreatedAtDesc(request.orderId(),
                        PaymentStateMachine.CAPTURED_STATES).stream().findFirst()
                .orElseThrow(() -> new CustomException(NO_CAPTURED_PAYMENT, HttpStatus.NOT_FOUND));
        LocalDateTime now = LocalDateTime.now(paymentClock);
        PaymentStatus status = PaymentStateMachine.refund(payment, request.amount(), now);
        paymentRepository.save(payment);
        RefundEntity refund = refundRepository.save(RefundEntity.builder()
                .id(UUID.randomUUID())
                .requestId(request.requestId())
                .paymentId(payment.getId())
                .orderId(request.orderId())
                .amount(request.amount().setScale(2, RoundingMode.HALF_UP))
                .reason(request.reason())
                .paymentStatus(status)
                .createdAt(now)
                .build());
        log.info("Refunded {} of payment {} for order {}: {}", refund.getAmount(), payment.getId(),
                payment.getOrderId(), status);
        return RefundResponseDto.from(refund);
    }
}
