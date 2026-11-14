# payment-service contributor guide

## Communication language

Use English for all communication related to this repository: responses, plans,
documentation, code comments, test names, commit messages and handoff notes.

## Contract

- The service boundary is the "Payments" and "Event feed" sections of the commerce-wave
  contract (`CONTRACTS.md`, to be published as `awesome-localstack/docs/PAYMENT_SERVICE_CONTRACT.md`).
  Implement exactly that boundary: public API, `PaymentDto`, statuses, test cards,
  idempotency, the signed result notification and card refunds from the backend event
  feed (`card-refund.requested`). The backend never calls payment-service; there is
  no inbound internal API.
- Order data always comes from the backend internal API through `BackendClient`;
  never add a local order table. A payment keeps only the order id and owner snapshot.
- Card data: only brand and last four digits are stored. Never log, persist or
  echo a card number or CVC (`CardDataLeakTest` checks logs and every table).
- `RestBackendClientContractTest` pins the internal-API requests (path, query,
  headers, verbatim signed body), including `GET /internal/v1/events`. Change it only
  together with the contract and the backend.
- Event feed handling: one local transaction per event (handle, record in
  `processed_events`, advance `event_cursors`); handlers implement `FeedEventHandler`,
  must be idempotent and must not throw for expected business outcomes (that would
  skip the event). Transient failures leave the cursor; others skip the event.
- Reads of another user's payment or order payments answer 404 like a missing one.
- Backend 4xx keep status and message; unavailability is 503 "Backend unavailable".
- Correlation ids (contract section "Correlation IDs"): `CorrelationFilter` runs first and owns
  the MDC keys `requestId`/`traceId`/`spanId`; `CorrelationInterceptor` (in `RestBackendClient`)
  sends `X-Request-Id` and a child `traceparent` on every backend call. Work that runs outside a
  request (outbox delivery, settlement, feed events) must open a `Spans` scope (which opens the
  `Correlation` scope and a span) from the stored or received ids and close it; never leave MDC
  values behind on a pooled thread.
- Tracing (lab contract section 4) is compiled in and switched at runtime by `TRACING_ENABLED`
  (`SwitchableSampler`); never gate tracing beans with `@ConditionalOn*` or Boot's
  `management.tracing.export.enabled` (AOT freezes them). With tracing off nothing may change:
  `CorrelationInterceptor` sends the hand-built child `traceparent` only then.
- Reservation guard: `POST /api/v1/payments` answers 409 "Order reservation expired" when the
  snapshot's optional `reservationExpiresAt` is not after now; a snapshot without it never expires.

# Verification Commands

- Fast local verify: `./mvnw -Pfast-verify verify`
- Default verify (CI default, PMD + JaCoCo 90/75 gate): `./mvnw verify`
- Tests only: `./mvnw test`
- Targeted mutation feedback: `./mvnw -Pmutation-testing test-compile pitest:mutationCoverage`
- Native image (needs Docker with >= 12 GB memory, not a local GraalVM):
  `docker build -t payment-service:local .` (JVM fallback: `docker build -f Dockerfile.jvm .`)
- Native smoke test: `scripts/native-smoke.sh payment-service:local`

The default test run needs neither Docker nor a backend: flow tests use the
in-memory `FakeBackend`, which implements the contract's result-notification rules
(signature check, idempotency per requestId, amountDue check, 409 "Order is not payable")
and serves an in-memory event feed, and a `MutableClock` that drives settlement delays,
outbox backoff and card expiry. The event feed poller is disabled in tests and called
explicitly (`EventFeedPoller.poll()`).

# Native-image rules

- The native image is PostgreSQL-only. H2 stays test-scoped; never add it to the
  `native` profile or the Dockerfile.
- No runtime classpath scanning, no reflection without hints, no bean
  conditions (`@Profile`, `@ConditionalOn*`) that must change per deployment
  (Spring AOT fixes them at build time; use runtime properties such as
  `payment.settlement.enabled` or `payment.events.enabled` instead).
- Add Jackson binding hints in `NativeRuntimeHints` for every new type sent
  to or read from the backend.
- After changing dependencies, hints or configuration, rebuild the image and
  run the native smoke test.

# Mutation-testing workflow

- PITest: run the profile above after normal verification when you change the
  card checks or provider, the payment state machine, the signature, the result
  outbox or dispatcher, settlement and 3-D Secure expiry, refunds, the per-order
  payment lock, the reservation guard (`InternalOrderDto`, `PaymentService`) or the event feed
  poller and its handlers. Narrow it
  for small changes, for example
  `./mvnw -Pmutation-testing test-compile pitest:mutationCoverage -DtargetClasses=com.awesome.payment.service.PaymentStateMachine -DtargetTests=com.awesome.payment.service.PaymentStateMachineTest`.
- Treat `NO_COVERAGE` as a reachability gap and `SURVIVED` as an assertion gap.
  For each meaningful survivor add a test that passes on the original and fails
  on the mutant. Known equivalent mutants: the Luhn `digit > 9` boundary (a
  doubled digit is never 9), `sum +=` to `-=` (a multiple of 10 either way), the
  backoff cap and error-truncation boundaries, the dispatcher's log-only
  "will retry"/"giving up" ternary, the poller's REQUIRES_NEW propagation (there is
  never an outer transaction) and cursor `>` boundary (re-saving the same value), and
  the refund reason truncation boundary (substring of the full length). Keep the run advisory; do not use the
  aggregate score as a gate.

# Change discipline

- Preserve unrelated working-tree changes.
- Keep README.md (run, env vars, profiles) in sync with `application*.yml`.
- Every controller endpoint needs `@Operation` (summary and description) and
  `@ApiResponse` entries; `OpenApiDocumentationTest` enforces it.

# Native smoke test

- `docker build -t payment-service:native . && scripts/native-smoke.sh payment-service:native`
  boots the native binary against a real Postgres (under a 160 MiB limit) with a
  busybox `nc` backend stub (JWKS, order snapshots, result sink) and checks
  migrations, JWT validation, card payments, idempotent replay, 3-D Secure,
  scheduled settlement, the signed result notification, a card refund read from the
  stub's event feed (types filter, cursor), 404 for other users' payments and orders,
  health, metrics, OpenAPI, correlation ids (response header, notification headers, ECS JSON
  log fields with `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`) and that no card number reaches the log. CI runs it on every push and
  pull request.
