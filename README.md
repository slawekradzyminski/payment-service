# payment-service

Simulated card payments for the Awesome Testing stack: a fake card provider with
deterministic test cards, 3-D Secure, asynchronous settlement, signed result
notifications to the backend and card refunds. The backend keeps users, JWT
issuance and orders; this service decides whether a card payment succeeds and
tells the backend.

- Contract: "Payments" section of the commerce-wave contract (`CONTRACTS.md`)
- Stack: Spring Boot 4.1 / Java 25, Spring AOT, GraalVM native image
- Port: 4004 · OpenAPI: `/v3/api-docs` (gateway: `/payment/v3/api-docs`) · Health: `/actuator/health` · Metrics: `/actuator/prometheus`

## Public API

All public endpoints need a backend access token with `ROLE_CLIENT` or `ROLE_ADMIN`;
clients see only their own orders and payments, admins see everything.

| Endpoint | Result |
| --- | --- |
| `POST /api/v1/payments` (header `Idempotency-Key`, `^[A-Za-z0-9_-]{8,64}$`) | 201 `PaymentDto`; replay 200 + `Idempotent-Replayed: true`; 400 validation / Luhn / expired / bad key; 403 not your order; 404 order; 409 not payable or key reused with another body; 503 backend down |
| `POST /api/v1/payments/{id}/3ds` `{code}` | 200; `123456` captures, anything else fails with `THREE_DS_FAILED`; 409 when not `REQUIRES_ACTION` |
| `GET /api/v1/payments/{id}` | 200 / 403 / 404 |
| `GET /api/v1/orders/{orderId}/payments` | payments of the order, newest first (the order is read from the backend: 404 / 403) |
| `GET /api/v1/admin/payments?status&page&size` | `ROLE_ADMIN`; page `{content,pageNumber,pageSize,totalElements,totalPages}`, newest first, size 1-100 (default 20) |

`PaymentDto {id, orderId, status, amount, currency, cardBrand, cardLast4, failureCode, failureMessage, nextAction, refundedAmount, createdAt, updatedAt}`;
`nextAction` is `{type: THREE_DS_CHALLENGE, hint}` while the payment is `REQUIRES_ACTION`, otherwise `null`.
Statuses: `REQUIRES_ACTION, PROCESSING, CAPTURED, FAILED, PARTIALLY_REFUNDED, REFUNDED, VOIDED`.
The amount is the order's `amountDue` (or `totalAmount` when the backend does not send it), USD, scale 2.

An order is payable when the backend reports it `PENDING` and it has no local payment in
`REQUIRES_ACTION`, `PROCESSING`, `CAPTURED`, `PARTIALLY_REFUNDED` or `REFUNDED`.
Failed and voided payments do not block a new attempt. A 3-D Secure challenge that
is not answered within `payment.three-ds-timeout` (default 15 min) fails with
`THREE_DS_EXPIRED`: the settlement job expires it, and so does a new payment attempt
for the same order or a late answer (which then gets 409), so the customer can retry
at once. The FAILED result is sent to the backend like any other failure.

Payment creation for one order is serialised with a row lock (`order_payment_locks`,
`SELECT ... FOR UPDATE`, Flyway `V2`), so two concurrent submissions can never both
become the active payment, and a double submit with the same `Idempotency-Key` is
answered as a replay. (A partial unique index was not used: H2, the test database,
does not support `WHERE` on indexes.)

### Test cards

Spaces and dashes are ignored. Expiry must not be before the current month.

| Number | Outcome |
| --- | --- |
| 4242 4242 4242 4242 | CAPTURED (VISA) |
| 5555 5555 5555 4444 | CAPTURED (MASTERCARD) |
| 4000 0000 0000 0002 | FAILED `CARD_DECLINED` |
| 4000 0000 0000 9995 | FAILED `INSUFFICIENT_FUNDS` |
| 4000 0000 0000 3220 | REQUIRES_ACTION (3-D Secure code 123456; expires after `payment.three-ds-timeout`) |
| 4000 0000 0000 0077 | PROCESSING, CAPTURED after `payment.settlement-delay` |
| 4000 0000 0000 0119 | PROCESSING, FAILED `PROVIDER_TIMEOUT` after the same delay |
| 4000 0000 0000 0259 | CAPTURED; the result is delivered to the backend twice with the same requestId |
| any other Luhn-valid number | CAPTURED (brand VISA / MASTERCARD / AMEX / OTHER from the prefix) |

Only brand and last four digits are stored. The `Idempotency-Key` fingerprint is
an HMAC of the request with the webhook secret, never the card number. Card
numbers and CVCs are never logged; `CardDto.toString()` masks them.

### Idempotency

Keys are scoped per caller. The same key with the same body (card number compared
without spaces) returns the original payment in its current state with 200 and
`Idempotent-Replayed: true`; another body is 409. Rejected requests (400/403/404/409)
do not consume the key.

## How it talks to the backend

`BackendClient` (Spring `RestClient`, 2 s connect / 10 s read timeout) calls
`/internal/v1` on `backend.base-url` with `X-Service-Token: PAYMENT_SERVICE_TOKEN`:

- `GET /internal/v1/orders/{id}`: order snapshot (`username`, `status`, `totalAmount`, `amountDue`);
- `POST /internal/v1/orders/{id}/payment`: result notification
  `{requestId, paymentId, status: CAPTURED|FAILED, amount, currency, failureCode}`, header
  `X-Payment-Signature: t=<epochSeconds>,v1=<hex HMAC-SHA256(PAYMENT_WEBHOOK_SECRET, t + "." + rawBody)>`.
  The stored body is sent byte for byte and re-signed on every attempt.
- `GET /internal/v1/jwks.json`: access-token verification keys.

Results go through a transactional outbox (`payment_outbox`): written with the
payment change, sent right after the commit, and retried every 30 seconds with
exponential backoff (30 s doubling to 30 min, at most 10 attempts) while the
backend answers 5xx or is unreachable. A 409 ("Order is not payable", for example
the order was cancelled during 3-D Secure) to a capture voids the payment
(`VOIDED`, `refundedAmount = amount`, `failureCode = ORDER_NOT_PAYABLE`). Any
other rejection (401 signature, 422 amount mismatch) is final and logged.

Authentication uses the backend's RS256 access tokens (roles in the `auth` claim),
verified with the public keys from the backend's JWKS, cached by `kid` and refetched
for an unknown `kid` at most once per `JWT_JWKS_REFRESH_INTERVAL`; HMAC tokens are
rejected. 401/403 bodies are `{"message": ...}`.

### Internal refund API (backend only, not in OpenAPI)

`POST /internal/v1/payments/refunds` with `X-Service-Token: PAYMENT_SERVICE_TOKEN`
(constant-time compare; missing or wrong: 401; blank configured token: 404),
body `{requestId, orderId, amount, reason}` → 200 `{refundId, paymentId, amount, status}`
where `status` is the payment status after the refund (`PARTIALLY_REFUNDED` or
`REFUNDED`). Idempotent per `requestId` (a repeat returns the first answer).
404 when the order has no captured payment, 409 when the amount exceeds what is
still refundable.

Local tables (Flyway `V1`–`V2`, schema `payment`): `payments`, `payment_idempotency_keys`,
`payment_outbox`, `refunds`, `order_payment_locks`.

## Run

Requires Java 25 (Maven comes from the wrapper). The service is PostgreSQL-only in
every profile; H2 is a test-scope dependency.

```bash
# Next to the backend's local profile (same localhost:5432/testdb, schema "payment"),
# backend at http://localhost:4001
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Profiles:

| Profile | Database | Backend | Notes |
| --- | --- | --- | --- |
| (none) | Postgres `jdbc:postgresql://localhost:5432/testdb?currentSchema=payment` | `http://localhost:4001` | token `local-payment-token`, secret `local-payment-webhook-secret` |
| `local` | same as above (user/password `postgres`) | `http://localhost:4001` | same defaults |
| `docker` | Postgres `jdbc:postgresql://postgres:5432/testdb?currentSchema=payment` | `http://backend:4001` | `PAYMENT_SERVICE_TOKEN` and `PAYMENT_WEBHOOK_SECRET` required |

### Environment variables

| Variable | Property | Default |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | active profiles | none (Postgres on localhost) |
| `BACKEND_BASE_URL` | `backend.base-url` | `http://localhost:4001` (`http://backend:4001` in `docker`) |
| `PAYMENT_SERVICE_TOKEN` | `payment.service-token` | `local-payment-token`; no default in `docker`; blank disables the refund API and sends backend calls without a token |
| `PAYMENT_WEBHOOK_SECRET` | `payment.webhook-secret` | `local-payment-webhook-secret`; no default in `docker`; must not be blank |
| `PAYMENT_SETTLEMENT_DELAY` | `payment.settlement-delay` | `PT10S` |
| `PAYMENT_THREE_DS_TIMEOUT` | `payment.three-ds-timeout` | `PT15M` (unanswered challenges fail with `THREE_DS_EXPIRED`) |
| `PAYMENT_SETTLEMENT_ENABLED` / `_INTERVAL` | `payment.settlement.*` | `true` / `PT2S` |
| `PAYMENT_NOTIFICATIONS_RETRY_ENABLED` / `_RETRY_INTERVAL` | `payment.notifications.*` | `true` / `PT30S` |
| `JWT_JWKS_REFRESH_INTERVAL` | `security.jwt.jwks-refresh-interval` | `10s` |
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | datasource | see profiles |
| `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE` | actuator exposure | `health,prometheus` |

## Test

```bash
./mvnw verify                 # unit, contract and flow tests + PMD + JaCoCo (90% lines / 75% branches)
./mvnw -Pfast-verify verify   # same tests without PMD and JaCoCo
```

No Docker, Postgres or backend is needed. Flow tests boot the service on H2 (`test`
profile) with `FakeBackend`, an in-memory implementation of the backend's result
rules that verifies the signature independently, and a `MutableClock`. The
settlement job and the outbox retry are switched off in tests and driven explicitly.
`RestBackendClientContractTest` pins every internal-API request with
`MockRestServiceServer`.

## Native image

```bash
docker build -t payment-service:local .          # needs ~9 GB for the builder (Docker VM >= 12 GB)
scripts/native-smoke.sh payment-service:local    # Postgres + busybox backend stub, 160 MiB limit
```

The build stage (`ghcr.io/graalvm/native-image-community:25`) runs
`./mvnw -Pnative native:compile` with the frozen `security/java/blocked.certs`
installed into the builder JDK; the runtime stage is `debian:trixie-slim` with the
single binary, running as UID 10001 with `CMD ["-Xmx64m"]` (160 MiB container
limit). The image keeps `bash` for a `/dev/tcp` Compose healthcheck. Published as
`slawekradzyminski/payment-service` and `ghcr.io/<owner>/payment-service` by the
tag-triggered `publish-image.yml`. `Dockerfile.jvm` builds a JVM fallback image.

## Mutation testing

- PITest: `./mvnw -Pmutation-testing test-compile pitest:mutationCoverage`
  (scope: `CardChecks`, `FakeCardProvider`, `PaymentStateMachine`, `PaymentService`,
  `SettlementJob`, `RefundService`, `OrderPaymentLocks`, `PaymentSignature`, `PaymentResultOutbox`,
  `PaymentResultDispatcher`, `ServiceTokenFilter`). Advisory, not a gate.
