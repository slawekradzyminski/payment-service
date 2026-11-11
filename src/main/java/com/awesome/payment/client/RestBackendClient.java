package com.awesome.payment.client;

import com.awesome.payment.controller.exception.CustomException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.function.Supplier;

/** {@link BackendClient} over HTTP with Spring's {@link RestClient}. */
@Slf4j
public class RestBackendClient implements BackendClient {

    public static final String SERVICE_TOKEN_HEADER = "X-Service-Token";
    public static final String SIGNATURE_HEADER = "X-Payment-Signature";

    private final RestClient restClient;

    public RestBackendClient(RestClient.Builder builder, String baseUrl, String serviceToken) {
        RestClient.Builder configured = builder
                .baseUrl(baseUrl)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                    throw BackendErrorMapper.fromResponse(
                            response.getStatusCode(), StreamUtils.copyToByteArray(response.getBody()));
                });
        if (serviceToken == null || serviceToken.isBlank()) {
            // Blank token = the payment integration is disabled; the backend will refuse our calls.
            log.warn("payment.service-token is blank: backend calls are sent without X-Service-Token");
        } else {
            configured.defaultHeader(SERVICE_TOKEN_HEADER, serviceToken);
        }
        this.restClient = configured.build();
    }

    @Override
    public InternalOrderDto getOrder(long orderId) {
        return required(() -> restClient.get()
                .uri("/internal/v1/orders/{id}", orderId)
                .retrieve()
                .body(InternalOrderDto.class));
    }

    @Override
    public PaymentResultResponse sendPaymentResult(long orderId, String rawBody, String signature) {
        // A String body goes through StringHttpMessageConverter unchanged, so the bytes on the
        // wire are exactly the bytes the signature was computed over.
        return call(() -> restClient.post()
                .uri("/internal/v1/orders/{id}/payment", orderId)
                .contentType(MediaType.APPLICATION_JSON)
                .header(SIGNATURE_HEADER, signature)
                .body(rawBody)
                .retrieve()
                .body(PaymentResultResponse.class));
    }

    @Override
    public JwkSet jwks() {
        return required(() -> restClient.get()
                .uri("/internal/v1/jwks.json")
                .retrieve()
                .body(JwkSet.class));
    }

    @Override
    public EventFeedPage events(long after, int limit, List<String> types) {
        return required(() -> restClient.get()
                .uri(uri -> uri.path("/internal/v1/events")
                        .queryParam("after", after)
                        .queryParam("limit", limit)
                        .queryParam("types", String.join(",", types))
                        .build())
                .retrieve()
                .body(EventFeedPage.class));
    }

    private static <T> T required(Supplier<T> request) {
        T body = call(request);
        if (body == null) {
            throw BackendErrorMapper.unavailable(new IllegalStateException("Backend returned an empty body"));
        }
        return body;
    }

    private static <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (CustomException e) {
            throw e;
        } catch (RestClientException e) {
            throw BackendErrorMapper.unavailable(e);
        }
    }
}
