package com.awesome.payment.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** The backend's answer to a payment result: the order and its status after applying it. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentResultResponse(Long orderId, OrderStatus orderStatus) {
}
