# payment-service

Simulated card payments for the Awesome Testing stack: a fake card provider with
deterministic test cards, 3-D Secure, asynchronous settlement, signed result
notifications to the backend and card refunds read from the backend's event feed.
The backend keeps users, JWT issuance and orders; this service decides whether a
card payment succeeds and tells the backend. The backend never calls this service.

- Contract: "Payments" and "Event feed" sections of the commerce-wave contract (`CONTRACTS.md`)
- Stack: Spring Boot 4.1 / Java 25, Spring AOT, GraalVM native image
- Port: 4004 · OpenAPI: `/v3/api-docs` (gateway: `/payment/v3/api-docs`) · Health: `/actuator/health` · Metrics: `/actuator/prometheus`

## Public API

All public endpoints need a backend access token with `ROLE_CLIENT` or `ROLE_ADMIN`;
clients see only their own orders and payments, admins see everything. Reading another
user's payment or order payments is answered 404 exactly like a missing one (as the
backend does), so the existence of other users' orders is not revealed; only paying
another user's order is 403, as the contract specifies.

| Endpoint | Result |
| --- | --- |
| `POST /api/v1/payments` (header `Idempotency-Key`, `^[A-Za-z0-9_-]{8,64}$`) | 201 `PaymentDto`; replay 200 + `Idempotent-Replayed: true`; 400 validation / Luhn / expired / bad key; 403 not your order; 404 order; 409 not payable, `Order reservation expired` or key reused with another body; 503 backend down |
| `POST /api/v1/payments/{id}/3ds` `{code}` | 200; `123456` captures, anything else fails with `THREE_DS_FAILED`; 404 missing or another user's payment; 409 when not `REQUIRES_ACTION` |
| `GET /api/v1/payments/{id}` | 200 / 404 (missing, or another user's payment) |
| `GET /api/v1/orders/{orderId}/payments` | payments of the order, newest first (the order is read from the backend; 404 "Order not found" when it is missing or another user's) |
| `GET /api/v1/admin/payments?status&page&size` | `ROLE_ADMIN`; page `{content,pageNumber,pageSize,totalElements,totalPages}`, newest first, size 1-100 (default 20) |

`PaymentDto {id, orderId, status, amount, currency, cardBrand, cardLast4, failureCode, failureMessage, nextAction, refundedAmount, createdAt, updatedAt}`;
`nextAction` is `{type: THREE_DS_CHALLENGE, hint}` while the payment is `REQUIRES_ACTION`, otherwise `null`.
Statuses: `REQUIRES_ACTION, PROCESSING, CAPTURED, FAILED, PARTIALLY_REFUNDED, REFUNDED, VOIDED`.
The amount is the order's `amountDue` (or `totalAmount` when the backend does not send it), USD, scale 2.

An order is payable when the backend reports it `PENDING`, its stock reservation has not
expired, and it has no local payment in
`REQUIRES_ACTION`, `PROCESSING`, `CAPTURED`, `PARTIALLY_REFUNDED` or `REFUNDED`.
Failed and voided payments do not block a new attempt. A 3-D Secure challenge that
is not answered within `payment.three-ds-timeout` (default 15 min) fails with
`THREE_DS_EXPIRED`: the settlement job expires it, and so does a new payment attempt
for the same order or a late answer (which then gets 409), so the customer can retry
at once. The FAILED result is sent to the backend like any other failure.

### Reservation guard (backend 3.21.0+)

From backend 3.21.0 a new order's stock is held only until `reservationExpiresAt`
(default 30 minutes after creation); the backend's expiry job then cancels the still
`PENDING` order. The order snapshot carries that optional field, and
`POST /api/v1/payments` answers **409 `{"message":"Order reservation expired"}`** when it
is not after the service clock's now (the backend expires at `<=` too), so no card is
charged for an order that is about to be cancelled. Order of the 409 checks: not
`PENDING` ("Order is not payable", also for an order the backend already cancelled),
then the expired reservation, then nothing left to pay or a payment in progress ("Order
is not payable"). A snapshot without the field (older backends, orders created with
reservations disabled, legacy orders) never expires here. An idempotent replay of a
payment made before the expiry still returns that payment.

The race the guard cannot close (an order paid in time whose capture reaches the backend
after the expiry: a `PROCESSING` card that settles later, or a 3-D Secure answer) is the
existing path: the backend answers the capture with 409 and the payment is voided
(`ReservationGuardFlowTest`).

Payment creation for one order is serialised with a row lock (`order_payment_locks`,
`SELECT ... FOR UPDATE`, Flyway `V2`), so two concurrent submissions can never both
become the active payment, and a double submit with the same `Idempotency-Key` is
answered as a replay. (A partial unique index was not used: H2, the test database,
does not support `WHERE` on indexes.) A missing lock row is created with
`INSERT ... ON CONFLICT DO NOTHING` in the payment's own transaction, and the card
checks and the backend order lookup run before that transaction, so a request
never needs a second pooled connection or keeps one busy while it waits for the
backend: many concurrent payments share the pool of three
(`spring.datasource.hikari.maximum-pool-size`).

Timestamps come from a clock truncated to microseconds, the precision PostgreSQL
stores, so the 201 of a new payment carries exactly the `createdAt`/`updatedAt`
that the replay and every later read return.

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

- `GET /internal/v1/orders/{id}`: order snapshot (`username`, `status`, `totalAmount`, `amountDue`,
  optional `reservationExpiresAt`);
- `POST /internal/v1/orders/{id}/payment`: result notification
  `{requestId, paymentId, status: CAPTURED|FAILED, amount, currency, failureCode}`, header
  `X-Payment-Signature: t=<epochSeconds>,v1=<hex HMAC-SHA256(PAYMENT_WEBHOOK_SECRET, t + "." + rawBody)>`.
  The stored body is sent byte for byte and re-signed on every attempt.
- `GET /internal/v1/jwks.json`: access-token verification keys;
- `GET /internal/v1/events?after={cursor}&limit=100&types=card-refund.requested`: the domain event feed
  `{events:[{sequence, id, type, occurredAt, orderId, username, data, requestId, traceId, spanId}], nextCursor, hasMore}`
  (the correlation ids are optional; `spanId` comes with backend 3.21.0).

Results go through a transactional outbox (`payment_outbox`): written with the
payment change, sent right after the commit (by the dispatcher's own worker thread,
so the committing request or job never waits for the backend while holding a
database connection), and retried every 30 seconds with
exponential backoff (30 s doubling to 30 min, at most 10 attempts) while the
backend answers 5xx or is unreachable. A 409 ("Order is not payable", for example
the order was cancelled during 3-D Secure) to a capture voids the payment
(`VOIDED`, `refundedAmount = amount`, `failureCode = ORDER_NOT_PAYABLE`). Any
other rejection (401 signature, 422 amount mismatch) is final and logged.

Outbox metrics (ADR 3 outbox convention, on `/actuator/prometheus`):

| Metric | Type | Meaning |
| --- | --- | --- |
| `payment.outbox.pending` (`payment_outbox_pending`) | gauge | rows not sent and not given up (`sent_at` null, `attempts` < 10), counted in the database on each scrape |
| `payment.outbox.failed` (`payment_outbox_failed_total`) | counter | rows this instance gave up on: a final rejection, or the 10th failed attempt. A 409 that voids the capture is handled, not counted |

Authentication uses the backend's RS256 access tokens (roles in the `auth` claim),
verified with the public keys from the backend's JWKS, cached by `kid` and refetched
for an unknown `kid` at most once per `JWT_JWKS_REFRESH_INTERVAL`; HMAC tokens are
rejected. 401/403 bodies are `{"message": ...}`.

### Card refunds from the event feed

The backend publishes `card-refund.requested` (data `{requestId, orderId, amount, reason}`)
when a booked refund has a card leg or a card-paid order is cancelled. `EventFeedPoller`
reads the feed every `payment.events.poll-interval` (default 5 s) from its cursor
(`event_cursors`, consumer `payment-service`), asking only for `card-refund.requested`,
up to 20 pages of 100 per poll. Each event is handled in one local transaction that
books the refund, records the event id in `processed_events` and advances the cursor:

- the same event id again (redelivery, rewound cursor) is skipped;
- the same `requestId` in another event books nothing (idempotent per `requestId`);
- an order without a captured card payment books nothing (logged);
- an amount above what is still refundable books nothing (logged as a warning);
- a malformed payload or any other non-transient failure is logged with the event id
  and skipped (recorded as processed, cursor advances), so the feed is never blocked;
- the backend being unavailable (or a transient database failure) stops the poll
  without moving the cursor; the next poll retries.

A refund leaves the payment `PARTIALLY_REFUNDED` or `REFUNDED`. payment-service has
no inbound internal API any more (the 1.0.0 `POST /internal/v1/payments/refunds`
endpoint and its service-token filter were removed).

### Correlation ids and logging

Every request passes `CorrelationFilter` first: a valid incoming `X-Request-Id`
(1–128 characters of `[A-Za-z0-9._-]`) is kept, anything else is replaced by a generated
UUID; the trace id of a valid W3C `traceparent` (`00-<32 hex>-<16 hex>-<2 hex>`, ids not all
zeros) is kept, otherwise a new trace starts. The MDC holds `requestId`, `traceId` and
`spanId` (a new span per hop) while the request runs, and the response always carries
`X-Request-Id` (also on 401/403). Every backend call sends the same `X-Request-Id` and a
child `traceparent` (same trace id, new parent id); a call without a context (a scheduled
feed poll) starts fresh ids.

The ids survive persisted hand-offs (Flyway `V4`, nullable columns):

- a payment stores `correlation_request_id` and `traceparent` of the request that created it;
- its result-outbox rows copy them, and every delivery (right after the commit, from the
  settlement job that finishes an asynchronous or expired payment, or from the retry job)
  runs and logs under them, so the backend receives the original `X-Request-Id` and a child
  of the original trace;
- a `card-refund.requested` feed event's optional `requestId` / `traceId` are put into the
  MDC while it is handled (invalid values are ignored), so the card refund logs with the
  trace of the cancel/refund request that caused it, and the refund row keeps them
  (`correlation_request_id`, `trace_id`).

### Tracing (off by default)

payment-service is traced with Spring Boot's OpenTelemetry starter (Micrometer Tracing,
OpenTelemetry bridge, OTLP HTTP exporter with the JDK HTTP client), compiled into the
native image. It is off unless `TRACING_ENABLED=true`; then spans go to
`TRACING_OTLP_ENDPOINT` (default `http://jaeger:4318/v1/traces`) with service name
`payment-service`:

| Span | Kind | Parent |
| --- | --- | --- |
| `http <method> <path>` (Spring Boot) | `SERVER` | the incoming W3C `traceparent` (the gateway always sends one), so the trace id is the one in the logs |
| `http <method>` to the backend (Spring Boot) | `CLIENT` | the current span; the backend gets this span's `traceparent` |
| `process <type>` (`EventFeedPoller`) | `CONSUMER` | the feed event's `traceId`/`spanId`: a cancellation and the refund it triggers are one trace. Without `spanId` (backends before 3.21.0) the trace id is kept under a made-up parent id; without ids it starts a new trace |
| `dispatch payment-result` (`PaymentResultDispatcher`, every attempt) | `PRODUCER` | the row's stored `traceparent` (the request that created the payment) |
| `settle payment` (`SettlementJob`) | internal | none: a new trace per settlement, linked to the span of the request that created the payment; the result is still dispatched in that request's trace |

Spring AOT fixes Spring Boot's tracing bean conditions when the native image is built,
so tracing is always wired and `TRACING_ENABLED` is read at runtime by the sampler
(`SwitchableSampler`): off, nothing is sampled (even under a sampled caller), so nothing is
recorded or exported; on, sampling is parent-based with `TRACING_SAMPLING_PROBABILITY`
(default `1.0`) for new traces, and a backend call outside any traced work (the feed
poll, the startup JWKS fetch) never starts a trace. Spring's observations of the
`@Scheduled` ticks and of Spring Security's filter chain are switched off (they would add a
trace every few seconds and five spans per request; their timers were not on any
dashboard); the HTTP server and client timers stay. With tracing off the backend calls
keep the hand-built child `traceparent` and every id stays as described above; with
tracing on, the MDC `traceId`/`spanId` and the stored `traceparent` are the current
span's. A stored `traceparent` always has the sampled flag, so with a probability below 1
a result dispatch can be sampled under an unsampled request.

Plain console lines carry `[traceId,requestId]` (`logging.pattern.correlation`).
`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` (set by the server Compose file) switches to
Spring Boot's ECS JSON, one object per line with `requestId`, `traceId` and `spanId` as
fields; this works in the native image (checked by the smoke test).

Local tables (Flyway `V1`–`V4`, schema `payment`): `payments`, `payment_idempotency_keys`,
`payment_outbox`, `refunds`, `order_payment_locks`, `event_cursors`, `processed_events`.

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
| `PAYMENT_SERVICE_TOKEN` | `payment.service-token` | `local-payment-token`; no default in `docker`; `X-Service-Token` on every backend call (blank sends none, and the backend refuses them) |
| `PAYMENT_WEBHOOK_SECRET` | `payment.webhook-secret` | `local-payment-webhook-secret`; no default in `docker`; must not be blank |
| `PAYMENT_SETTLEMENT_DELAY` | `payment.settlement-delay` | `PT10S` |
| `PAYMENT_THREE_DS_TIMEOUT` | `payment.three-ds-timeout` | `PT15M` (unanswered challenges fail with `THREE_DS_EXPIRED`) |
| `PAYMENT_SETTLEMENT_ENABLED` / `_INTERVAL` | `payment.settlement.*` | `true` / `PT2S` |
| `PAYMENT_NOTIFICATIONS_RETRY_ENABLED` / `_RETRY_INTERVAL` | `payment.notifications.*` | `true` / `PT30S` |
| `PAYMENT_EVENTS_ENABLED` / `PAYMENT_EVENTS_POLL_INTERVAL` | `payment.events.enabled` / `.poll-interval` | `true` / `PT5S` (event feed poller) |
| `JWT_JWKS_REFRESH_INTERVAL` | `security.jwt.jwks-refresh-interval` | `10s` |
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | datasource | see profiles |
| `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE` | actuator exposure | `health,prometheus` |
| `LOGGING_STRUCTURED_FORMAT_CONSOLE` | `logging.structured.format.console` | unset (plain lines with `[traceId,requestId]`); `ecs` for JSON lines |
| `TRACING_ENABLED` | `tracing.enabled` | `false` (no span is sampled or exported) |
| `TRACING_OTLP_ENDPOINT` | `management.opentelemetry.tracing.export.otlp.endpoint` | `http://jaeger:4318/v1/traces` (OTLP over HTTP) |
| `TRACING_SAMPLING_PROBABILITY` | `management.tracing.sampling.probability` | `1.0` (new traces only; a caller's decision is followed) |

## Test

```bash
./mvnw verify                 # unit, contract and flow tests + PMD + JaCoCo (90% lines / 75% branches)
./mvnw -Pfast-verify verify   # same tests without PMD and JaCoCo
```

No Docker, Postgres or backend is needed. Flow tests boot the service on H2 (`test`
profile) with `FakeBackend`, an in-memory implementation of the backend's result
rules that verifies the signature independently and serves an in-memory event feed,
and a `MutableClock`. The settlement job, the outbox retry and the event feed poller
are switched off in tests and driven explicitly; flow-test helpers wait for the
dispatcher's after-commit deliveries before they return. H2 runs in PostgreSQL mode
(for `ON CONFLICT DO NOTHING`), and `ConcurrentPaymentFlowTest` sends 12 simultaneous
first payments through the production pool of three connections.
`RestBackendClientContractTest` pins every internal-API request with
`MockRestServiceServer`, including the `X-Request-Id` and child `traceparent` headers.
`FakeBackend` records the correlation each call was made under, `CorrelationFlowTest`
follows the ids through payments, settlement, retries and feed refunds, and
`TracingFlowTest` and `BackendCallPropagationFlowTest` boot the service with tracing on and
an in-memory span exporter (and the real `RestBackendClient` against a local HTTP stub), and
`StructuredLoggingFlowTest` attaches the ECS encoder to check the JSON fields and that no
JSON line contains a card number or CVC (`CardDataLeakTest` checks the plain lines).

## Native image

```bash
docker build -t payment-service:local .          # needs ~9 GB for the builder (Docker VM >= 12 GB)
scripts/native-smoke.sh payment-service:local    # Postgres + busybox backend stub, 160 MiB limit, ECS JSON logs
```

The build stage (`ghcr.io/graalvm/native-image-community:25`) runs
`./mvnw -Pnative native:compile` with the frozen `security/java/blocked.certs`
installed into the builder JDK; the runtime stage is `debian:trixie-slim` with the
single binary, running as UID 10001 with `CMD ["-Xmx64m"]` (160 MiB container
limit). The image keeps `bash` for a `/dev/tcp` Compose healthcheck. Published as
`slawekradzyminski/payment-service` and `ghcr.io/<owner>/payment-service` by the
tag-triggered `publish-image.yml`. `Dockerfile.jvm` builds a JVM fallback image.

## Release pipeline

Pushing a `vX.Y.Z` tag runs `.github/workflows/publish-image.yml`:

1. `validate` checks that the commit is on `master` and that the tag matches the project version.
2. `check-prior-verification` reads the git tree of the tagged commit and compares it with
   the trees of the commits behind the last 100 successful `ci.yml` runs (GitHub API,
   `actions: read`). PRs are squash-merged, so the tag commit is new but its tree is the
   tree of the PR head that passed CI (when the branch was up to date with `master`), or
   of the `master` push whose run has already finished. For `pull_request` runs GitHub
   tests the merge commit, which has that same tree when the branch is up to date.
3. When a matching run exists, the `verify` job (`./mvnw verify` plus the amd64 native
   build and smoke test) is skipped and the run summary links the CI run that verified
   the tree. Otherwise it runs as before. Manual (`workflow_dispatch`) prereleases always
   run it, and any API error falls back to the full verification. A failed or cancelled
   `verify` still blocks the release.
4. `build` compiles the native image on an amd64 and an arm64 runner, pushes each by
   digest and runs `scripts/native-smoke.sh` against that pushed image on both paths, so
   every published architecture is booted against Postgres; `publish` merges the digests.

A tag whose tree already passed `ci.yml` publishes in about 14 minutes instead of 20.

The release does not reuse the image from the PR: `ci.yml` builds `payment-service:native`
locally and never pushes it, and the published image carries the release commit in its
labels and is built on both architectures.

## Mutation testing

- PITest: `./mvnw -Pmutation-testing test-compile pitest:mutationCoverage`
  (scope: `CardChecks`, `FakeCardProvider`, `PaymentStateMachine`, `PaymentService`,
  `SettlementJob`, `RefundService`, `OrderPaymentLocks`, `PaymentSignature`, `PaymentResultOutbox`,
  `PaymentResultDispatcher`, `EventFeedPoller`, `CardRefundRequestedHandler`). Advisory, not a gate.
