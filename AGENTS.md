# payment-service contributor guide

## Communication language

Use English for all communication related to this repository: responses, plans,
documentation, code comments, test names, commit messages and handoff notes.

## Contract

- The service boundary is the "Payments" section of the commerce-wave contract
  (`CONTRACTS.md`, to be published as `awesome-localstack/docs/PAYMENT_SERVICE_CONTRACT.md`).
  Implement exactly that boundary: public API, `PaymentDto`, statuses, test cards,
  idempotency, the signed result notification and the refund endpoint.
- Order data always comes from the backend internal API through `BackendClient`;
  never add a local order table. A payment keeps only the order id and owner snapshot.
- Card data: only brand and last four digits are stored. Never log, persist or
  echo a card number or CVC (`CardDataLeakTest` checks logs and every table).
- `RestBackendClientContractTest` pins the internal-API requests (path, headers,
  verbatim signed body). Change it only together with the contract and the backend.
- Backend 4xx keep status and message; unavailability is 503 "Backend unavailable".

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
(signature check, idempotency per requestId, amountDue check, 409 "Order is not payable"),
and a `MutableClock` that drives settlement delays, outbox backoff and card expiry.

# Native-image rules

- The native image is PostgreSQL-only. H2 stays test-scoped; never add it to the
  `native` profile or the Dockerfile.
- No runtime classpath scanning, no reflection without hints, no bean
  conditions (`@Profile`, `@ConditionalOn*`) that must change per deployment
  (Spring AOT fixes them at build time; use runtime properties such as
  `payment.settlement.enabled` instead).
- Add Jackson binding hints in `NativeRuntimeHints` for every new type sent
  to or read from the backend.
- After changing dependencies, hints or configuration, rebuild the image and
  run the native smoke test.

# Mutation-testing workflow

- PITest: run the profile above after normal verification when you change the
  card checks or provider, the payment state machine, the signature, the result
  outbox or dispatcher, settlement and 3-D Secure expiry, refunds, the per-order
  payment lock or the service-token filter. Narrow it
  for small changes, for example
  `./mvnw -Pmutation-testing test-compile pitest:mutationCoverage -DtargetClasses=com.awesome.payment.service.PaymentStateMachine -DtargetTests=com.awesome.payment.service.PaymentStateMachineTest`.
- Treat `NO_COVERAGE` as a reachability gap and `SURVIVED` as an assertion gap.
  For each meaningful survivor add a test that passes on the original and fails
  on the mutant. Known equivalent mutants: the Luhn `digit > 9` boundary (a
  doubled digit is never 9), `sum +=` to `-=` (a multiple of 10 either way), the
  backoff cap and error-truncation boundaries, and the dispatcher's log-only
  "will retry"/"giving up" ternary. Keep the run advisory; do not use the
  aggregate score as a gate.

# Change discipline

- Preserve unrelated working-tree changes.
- Keep README.md (run, env vars, profiles) in sync with `application*.yml`.
- Every controller endpoint needs `@Operation` (summary and description) and
  `@ApiResponse` entries; `OpenApiDocumentationTest` enforces it. The internal
  refund endpoint is `@Hidden` and excluded from `/v3/api-docs`.

# Native smoke test

- `docker build -t payment-service:native . && scripts/native-smoke.sh payment-service:native`
  boots the native binary against a real Postgres (under a 160 MiB limit) with a
  busybox `nc` backend stub (JWKS, order snapshots, result sink) and checks
  migrations, JWT validation, card payments, idempotent replay, 3-D Secure,
  scheduled settlement, the signed result notification, refunds, health, metrics,
  OpenAPI and that no card number reaches the log. CI runs it on every push and
  pull request.
