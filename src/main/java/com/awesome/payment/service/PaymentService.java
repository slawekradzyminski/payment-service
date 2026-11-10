package com.awesome.payment.service;

import com.awesome.payment.client.BackendClient;
import com.awesome.payment.client.InternalOrderDto;
import com.awesome.payment.client.OrderStatus;
import com.awesome.payment.controller.exception.CustomException;
import com.awesome.payment.dto.payment.CardDto;
import com.awesome.payment.dto.payment.CreatePaymentDto;
import com.awesome.payment.dto.payment.PaymentDto;
import com.awesome.payment.entity.IdempotencyKeyEntity;
import com.awesome.payment.entity.PaymentEntity;
import com.awesome.payment.entity.PaymentStatus;
import com.awesome.payment.entity.Settlement;
import com.awesome.payment.repository.IdempotencyKeyRepository;
import com.awesome.payment.repository.PaymentRepository;
import com.awesome.payment.security.CustomPrincipal;
import com.awesome.payment.service.notification.PaymentResultOutbox;
import com.awesome.payment.service.notification.PaymentSignature;
import com.awesome.payment.service.provider.CardChecks;
import com.awesome.payment.service.provider.FakeCardProvider;
import com.awesome.payment.service.provider.ProviderDecision;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Creates card payments for backend orders and drives them through the simulated provider. */
@Slf4j
@Service
public class PaymentService {

    public static final String CURRENCY = "USD";
    static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");
    static final String INVALID_KEY = "Idempotency-Key header is required and must match ^[A-Za-z0-9_-]{8,64}$";
    static final String KEY_REUSED = "Idempotency-Key was already used with a different request";
    static final String NOT_PAYABLE = "Order is not payable";
    static final String NOT_YOUR_ORDER = "You can only pay for your own orders";
    static final String PAYMENT_NOT_FOUND = "Payment not found";
    static final String ACCESS_DENIED = "Access denied";
    static final String NO_ACTION_REQUIRED = "Payment does not require action";

    private final PaymentRepository paymentRepository;
    private final IdempotencyKeyRepository idempotencyKeys;
    private final BackendClient backendClient;
    private final FakeCardProvider provider;
    private final PaymentResultOutbox outbox;
    private final PaymentSignature signature;
    private final Clock paymentClock;
    private final Duration settlementDelay;

    public PaymentService(PaymentRepository paymentRepository, IdempotencyKeyRepository idempotencyKeys,
                          BackendClient backendClient, FakeCardProvider provider, PaymentResultOutbox outbox,
                          PaymentSignature signature, Clock paymentClock,
                          @Value("${payment.settlement-delay:PT10S}") Duration settlementDelay) {
        this.paymentRepository = paymentRepository;
        this.idempotencyKeys = idempotencyKeys;
        this.backendClient = backendClient;
        this.provider = provider;
        this.outbox = outbox;
        this.signature = signature;
        this.paymentClock = paymentClock;
        this.settlementDelay = settlementDelay;
    }

    /** A created payment, or the earlier one when the request is an idempotent replay. */
    public record CreateResult(PaymentDto payment, boolean replayed) {
    }

    /**
     * Pays an order. Order of checks: Idempotency-Key format (400), replay or conflicting reuse
     * (200 / 409), card number and expiry (400), order exists (404), ownership (403), payable (409).
     */
    @Transactional
    public CreateResult create(CustomPrincipal principal, String idempotencyKey, CreatePaymentDto request) {
        if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new CustomException(INVALID_KEY, HttpStatus.BAD_REQUEST);
        }
        String fingerprint = fingerprint(request);
        Optional<IdempotencyKeyEntity> earlier = idempotencyKeys.findByUsernameAndIdempotencyKey(principal.username(), idempotencyKey);
        if (earlier.isPresent()) {
            if (!earlier.get().getRequestHash().equals(fingerprint)) {
                throw new CustomException(KEY_REUSED, HttpStatus.CONFLICT);
            }
            return new CreateResult(PaymentDto.from(load(earlier.get().getPaymentId())), true);
        }
        ProviderDecision decision = provider.authorize(request.card());
        InternalOrderDto order = backendClient.getOrder(request.orderId());
        if (!principal.isAdmin() && !principal.username().equals(order.username())) {
            throw new CustomException(NOT_YOUR_ORDER, HttpStatus.FORBIDDEN);
        }
        BigDecimal amount = order.payableAmount() == null ? BigDecimal.ZERO
                : order.payableAmount().setScale(2, RoundingMode.HALF_UP);
        if (order.status() != OrderStatus.PENDING || amount.signum() <= 0
                || paymentRepository.existsByOrderIdAndStatusIn(order.id(), PaymentStateMachine.BLOCKING)) {
            throw new CustomException(NOT_PAYABLE, HttpStatus.CONFLICT);
        }

        PaymentEntity payment = paymentRepository.save(newPayment(order, amount, decision));
        idempotencyKeys.save(IdempotencyKeyEntity.builder()
                .username(principal.username())
                .idempotencyKey(idempotencyKey)
                .requestHash(fingerprint)
                .paymentId(payment.getId())
                .createdAt(payment.getCreatedAt())
                .build());
        if (payment.getStatus() == PaymentStatus.CAPTURED || payment.getStatus() == PaymentStatus.FAILED) {
            outbox.enqueue(payment, decision.notifyTwice() ? 2 : 1);
        }
        log.info("Payment {} for order {} by {}: {} ({} ending {})", payment.getId(), order.id(),
                principal.username(), payment.getStatus(), payment.getCardBrand(), payment.getCardLast4());
        return new CreateResult(PaymentDto.from(payment), false);
    }

    /** Answers the 3-D Secure challenge: {@code 123456} captures, anything else fails the payment. */
    @Transactional
    public PaymentDto completeThreeDs(CustomPrincipal principal, UUID paymentId, String code) {
        PaymentEntity payment = loadVisible(principal, paymentId);
        if (payment.getStatus() != PaymentStatus.REQUIRES_ACTION) {
            throw new CustomException(NO_ACTION_REQUIRED, HttpStatus.CONFLICT);
        }
        LocalDateTime now = LocalDateTime.now(paymentClock);
        if (provider.threeDsApproved(code)) {
            PaymentStateMachine.capture(payment, now);
        } else {
            PaymentStateMachine.fail(payment, FakeCardProvider.THREE_DS_FAILED,
                    FakeCardProvider.failureMessage(FakeCardProvider.THREE_DS_FAILED), now);
        }
        paymentRepository.save(payment);
        outbox.enqueue(payment, 1);
        log.info("3-D Secure for payment {} answered: {}", payment.getId(), payment.getStatus());
        return PaymentDto.from(payment);
    }

    @Transactional(readOnly = true)
    public PaymentDto get(CustomPrincipal principal, UUID paymentId) {
        return PaymentDto.from(loadVisible(principal, paymentId));
    }

    /** The order's payments, newest first; the order must exist and belong to the caller (or caller is admin). */
    @Transactional(readOnly = true)
    public List<PaymentDto> forOrder(CustomPrincipal principal, long orderId) {
        InternalOrderDto order = backendClient.getOrder(orderId);
        if (!principal.isAdmin() && !principal.username().equals(order.username())) {
            throw new CustomException(ACCESS_DENIED, HttpStatus.FORBIDDEN);
        }
        return paymentRepository.findByOrderIdOrderByCreatedAtDescIdDesc(orderId).stream()
                .map(PaymentDto::from).toList();
    }

    @Transactional(readOnly = true)
    public Page<PaymentDto> all(PaymentStatus status, Pageable pageable) {
        Page<PaymentEntity> page = status == null ? paymentRepository.findAll(pageable)
                : paymentRepository.findByStatus(status, pageable);
        return page.map(PaymentDto::from);
    }

    private PaymentEntity newPayment(InternalOrderDto order, BigDecimal amount, ProviderDecision decision) {
        LocalDateTime now = LocalDateTime.now(paymentClock);
        PaymentEntity payment = PaymentEntity.builder()
                .id(UUID.randomUUID())
                .orderId(order.id())
                .username(order.username())
                .amount(amount)
                .refundedAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                .currency(CURRENCY)
                .cardBrand(decision.brand())
                .cardLast4(decision.last4())
                .createdAt(now)
                .updatedAt(now)
                .build();
        switch (decision.outcome()) {
            case CAPTURED -> payment.setStatus(PaymentStatus.CAPTURED);
            case FAILED -> {
                payment.setStatus(PaymentStatus.FAILED);
                payment.setFailureCode(decision.failureCode());
                payment.setFailureMessage(FakeCardProvider.failureMessage(decision.failureCode()));
            }
            case REQUIRES_ACTION -> payment.setStatus(PaymentStatus.REQUIRES_ACTION);
            case SETTLES, TIMES_OUT -> {
                payment.setStatus(PaymentStatus.PROCESSING);
                payment.setSettlement(decision.outcome() == ProviderDecision.Outcome.SETTLES
                        ? Settlement.CAPTURE : Settlement.TIMEOUT);
                payment.setSettleAt(now.plus(settlementDelay));
            }
        }
        return payment;
    }

    private PaymentEntity loadVisible(CustomPrincipal principal, UUID paymentId) {
        PaymentEntity payment = load(paymentId);
        if (!principal.isAdmin() && !principal.username().equals(payment.getUsername())) {
            throw new CustomException(ACCESS_DENIED, HttpStatus.FORBIDDEN);
        }
        return payment;
    }

    private PaymentEntity load(UUID paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new CustomException(PAYMENT_NOT_FOUND, HttpStatus.NOT_FOUND));
    }

    /**
     * Fingerprint of the request for Idempotency-Key replays: an HMAC with the server secret, so
     * the stored value cannot be brute-forced back to a card number.
     */
    String fingerprint(CreatePaymentDto request) {
        CardDto card = request.card();
        return signature.hmacHex(String.join("\n", "payment-request-v1", String.valueOf(request.orderId()),
                CardChecks.digits(card.number()), String.valueOf(card.expiryMonth()),
                String.valueOf(card.expiryYear()), card.cvc(), card.holderName()));
    }
}
