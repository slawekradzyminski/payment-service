#!/usr/bin/env bash
# Boots the payment-service image against a real Postgres and checks that the
# native binary migrates the schema, validates backend-style RS256 JWTs against
# a JWKS, takes card payments (JPA writes, outbox, signed result notification,
# scheduled settlement), books card refunds from the backend event feed and
# serves health, metrics and OpenAPI. Instead of a backend, a busybox nc stub
# publishes the JWKS at /internal/v1/jwks.json, answers GET /internal/v1/orders/{id}
# with a PENDING order of "smoke-client", records POST /internal/v1/orders/{id}/payment
# and serves GET /internal/v1/events: one card-refund.requested event for order 101
# once the script publishes it (touch /tmp/publish-refund in the stub). The app logs ECS JSON
# (LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs, as on the server), and the stub records the
# X-Request-Id and traceparent of every call, so correlation ids are checked end to end.
# Order 106's reservation has expired (409 "Order reservation expired"). With SMOKE_TRACING=1 the
# app runs with TRACING_ENABLED=true and exports its spans over OTLP HTTP to the stub
# (POST /v1/traces); every other check is the same, since tracing keeps the trace ids.
#   scripts/native-smoke.sh payment-service:native
#   SMOKE_TRACING=1 scripts/native-smoke.sh payment-service:native
set -euo pipefail

IMAGE="${1:?usage: native-smoke.sh IMAGE}"
BUSYBOX_IMAGE="busybox:1.37.0@sha256:a7dbc319b1438115a207e11930dc4bb3e643dcb3ec0bdd694bb36cd8fad289ec"
POSTGRES_IMAGE="postgres:16-alpine@sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777"
RUN_ID="payment-smoke-$$"
NETWORK="$RUN_ID"
PORT="${SMOKE_PORT:-14004}"
SERVICE_TOKEN="native-smoke-service-token"
TRACE_ID="4bf92f3577b34da6a3ce929d0e0e4736"
CALLER_SPAN="00f067aa0ba902b7"
CANCEL_TRACE_ID="0af7651916cd43dd8448eb211c80319c"
WEBHOOK_SECRET="native-smoke-webhook-secret"
TRACING="${SMOKE_TRACING:-0}"

cleanup() {
  status=$?
  if [[ $status -ne 0 ]]; then
    echo "--- payment-service log ---" >&2
    docker logs "$RUN_ID-app" >&2 2>&1 || true
    echo "--- backend stub requests ---" >&2
    docker exec "$RUN_ID-backend" cat /tmp/requests.log >&2 2>&1 || true
  fi
  docker rm -f "$RUN_ID-app" "$RUN_ID-db" "$RUN_ID-backend" >/dev/null 2>&1 || true
  rm -rf "$WORK"
  docker network rm "$NETWORK" >/dev/null 2>&1 || true
  exit $status
}
WORK=$(mktemp -d)
trap cleanup EXIT

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
hex_b64url() { tr -d '\n' | perl -e 'local $/; print pack("H*", <STDIN>)' | b64url; }

# RS256 signing key and its JWKS, published like the backend's /internal/v1/jwks.json.
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$WORK/key.pem" 2>/dev/null
MODULUS=$(openssl rsa -in "$WORK/key.pem" -noout -modulus | cut -d= -f2 | hex_b64url)
EXPONENT=$(printf '010001' | hex_b64url)
KID="native-smoke-key"
mkdir -p "$WORK/srv"
printf '{"keys":[{"kty":"RSA","use":"sig","alg":"RS256","kid":"%s","n":"%s","e":"%s"}]}' \
  "$KID" "$MODULUS" "$EXPONENT" > "$WORK/srv/jwks.json"

# One HTTP exchange per connection (nc -lk -e): JWKS, order snapshots, payment results and the event feed.
cat > "$WORK/srv/backend.sh" <<'STUB'
#!/bin/sh
read -r method path _
length=0
signature=""
token=""
request_id=""
traceparent=""
while IFS= read -r line; do
  line=$(printf '%s' "$line" | tr -d '\r')
  [ -z "$line" ] && break
  case "$line" in
    [Cc]ontent-[Ll]ength:*) length=${line#*: } ;;
    [Xx]-[Pp]ayment-[Ss]ignature:*) signature=${line#*: } ;;
    [Xx]-[Ss]ervice-[Tt]oken:*) token=${line#*: } ;;
    [Xx]-[Rr]equest-[Ii]d:*) request_id=${line#*: } ;;
    [Tt]raceparent:*) traceparent=${line#*: } ;;
  esac
done
body=""
if [ "$path" = /v1/traces ]; then
  # OTLP protobuf (binary): only count the export.
  [ "$length" -gt 0 ] && head -c "$length" > /dev/null
  printf 'OTLP %s\n' "$length" >> /tmp/requests.log
  printf 'HTTP/1.1 200 OK\r\nContent-Type: application/x-protobuf\r\nContent-Length: 0\r\nConnection: close\r\n\r\n'
  exit 0
fi
[ "$length" -gt 0 ] && body=$(head -c "$length")
status="200 OK"
case "$method $path" in
  "GET /internal/v1/jwks.json") response=$(cat /srv/jwks.json) ;;
  "GET /internal/v1/orders/"*)
    id=${path##*/}
    # Backend 3.21.0 snapshot: order 106's stock reservation has expired, the others run until 2099.
    expires="2099-01-01T00:00:00.123Z"
    [ "$id" = 106 ] && expires="2020-01-01T00:00:00.000Z"
    response=$(printf '{"id":%s,"username":"smoke-client","status":"PENDING","totalAmount":42.50,"amountDue":42.50,"reservationExpiresAt":"%s","items":[]}' "$id" "$expires") ;;
  "GET /internal/v1/events?"*)
    printf 'FEED %s|%s\n' "$path" "$token" >> /tmp/requests.log
    after=$(printf '%s' "$path" | sed -n 's/.*[?&]after=\([0-9]*\).*/\1/p')
    if [ "${after:-0}" = 0 ] && [ -f /tmp/publish-refund ]; then
      response='{"events":[{"sequence":1,"id":"00000000-0000-0000-0000-0000000000e1","type":"card-refund.requested","occurredAt":"2026-11-10T12:00:00Z","orderId":101,"username":"smoke-client","data":{"requestId":"00000000-0000-0000-0000-00000000a001","orderId":101,"amount":12.50,"reason":"smoke"},"requestId":"smoke-cancel-1","traceId":"0af7651916cd43dd8448eb211c80319c","spanId":"b7ad6b7169203331"}],"nextCursor":1,"hasMore":false}'
    else
      response=$(printf '{"events":[],"nextCursor":%s,"hasMore":false}' "${after:-0}")
    fi ;;
  "POST /internal/v1/orders/"*/payment)
    id=${path#/internal/v1/orders/}
    id=${id%/payment}
    printf 'RESULT %s|%s|%s|%s\n' "$id" "$token" "$signature" "$body" >> /tmp/requests.log
    response=$(printf '{"orderId":%s,"orderStatus":"PAID"}' "$id") ;;
  *) status="404 Not Found"; response='{"message":"Not found"}' ;;
esac
printf '%s %s\n' "$method" "$path" >> /tmp/requests.log
printf 'CORR %s %s|%s|%s\n' "$method" "$path" "$request_id" "$traceparent" >> /tmp/requests.log
printf 'HTTP/1.1 %s\r\nContent-Type: application/json\r\nContent-Length: %s\r\nConnection: close\r\n\r\n%s' \
  "$status" "${#response}" "$response"
STUB
chmod -R a+rX "$WORK/srv"
chmod a+x "$WORK/srv/backend.sh"

token() {
  local now header payload signature
  now=$(date +%s)
  header=$(printf '{"alg":"RS256","kid":"%s"}' "$KID" | b64url)
  payload=$(printf '{"sub":"%s","auth":[{"authority":"%s"}],"iat":%d,"exp":%d}' "$1" "$2" "$now" $((now + 600)) | b64url)
  signature=$(printf '%s.%s' "$header" "$payload" | openssl dgst -sha256 -sign "$WORK/key.pem" -binary | b64url)
  printf '%s.%s.%s' "$header" "$payload" "$signature"
}
hmac_token() {
  local header payload signature
  header=$(printf '{"alg":"HS256","kid":"%s"}' "$KID" | b64url)
  payload=$(printf '{"sub":"smoke-admin","auth":[{"authority":"ROLE_ADMIN"}],"exp":%d}' $(( $(date +%s) + 600 )) | b64url)
  signature=$(printf '%s.%s' "$header" "$payload" | openssl dgst -sha256 -hmac "$MODULUS" -binary | b64url)
  printf '%s.%s.%s' "$header" "$payload" "$signature"
}
status_of() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
expect() {
  if [[ "$2" != "$3" ]]; then
    echo "FAIL $1: expected $3, got $2" >&2
    exit 1
  fi
  echo "PASS $1"
}
contains() {
  if ! grep -q -- "$3" <<< "$2"; then
    echo "FAIL $1: $3 not in $2" >&2
    exit 1
  fi
  echo "PASS $1"
}
field() { sed -n "s/.*\"$1\":\"\\{0,1\\}\\([^\",}]*\\).*/\\1/p" <<< "$2"; }

docker network create "$NETWORK" >/dev/null
docker run -d --name "$RUN_ID-backend" --network "$NETWORK" -v "$WORK/srv:/srv:ro" \
  "$BUSYBOX_IMAGE" nc -lk -p 4001 -e /srv/backend.sh >/dev/null
docker run -d --name "$RUN_ID-db" --network "$NETWORK" \
  -e POSTGRES_DB=payment -e POSTGRES_USER=payment -e POSTGRES_PASSWORD=payment \
  "$POSTGRES_IMAGE" >/dev/null
for _ in $(seq 1 60); do
  docker exec "$RUN_ID-db" pg_isready -U payment -d payment >/dev/null 2>&1 && break
  sleep 1
done

tracing_env=(-e TRACING_ENABLED=false)
if [[ "$TRACING" == 1 ]]; then
  tracing_env=(-e TRACING_ENABLED=true -e TRACING_OTLP_ENDPOINT="http://$RUN_ID-backend:4001/v1/traces")
fi
started=$(date +%s)
docker run -d --name "$RUN_ID-app" --network "$NETWORK" --memory 160m -p "$PORT:4004" "${tracing_env[@]}" \
  -e SPRING_PROFILES_ACTIVE=docker \
  -e SPRING_DATASOURCE_URL="jdbc:postgresql://$RUN_ID-db:5432/payment?currentSchema=payment" \
  -e SPRING_DATASOURCE_USERNAME=payment -e SPRING_DATASOURCE_PASSWORD=payment \
  -e PAYMENT_SERVICE_TOKEN="$SERVICE_TOKEN" -e PAYMENT_WEBHOOK_SECRET="$WEBHOOK_SECRET" \
  -e PAYMENT_SETTLEMENT_DELAY=PT2S -e PAYMENT_SETTLEMENT_INTERVAL=PT1S -e PAYMENT_EVENTS_POLL_INTERVAL=PT1S \
  -e BACKEND_BASE_URL="http://$RUN_ID-backend:4001" -e LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs \
  "$IMAGE" >/dev/null

base="http://localhost:$PORT"
for _ in $(seq 1 90); do
  [[ "$(status_of "$base/actuator/health")" == 200 ]] && break
  sleep 1
done
expect "health" "$(status_of "$base/actuator/health")" 200
echo "ready after $(( $(date +%s) - started ))s"

expect "prometheus metrics" "$(status_of "$base/actuator/prometheus")" 200
metrics=$(curl -sf "$base/actuator/prometheus")
contains "outbox pending gauge" "$metrics" '^payment_outbox_pending '
contains "outbox failed counter" "$metrics" '^payment_outbox_failed_total '
docs=$(curl -sf "$base/v3/api-docs")
contains "OpenAPI lists payment paths" "$docs" '"/api/v1/payments/{id}/3ds"'
if grep -q '/internal/' <<< "$docs"; then echo "FAIL OpenAPI exposes the internal API" >&2; exit 1; fi
curl -sf -H 'Host: awesome.byst.re' -H 'X-Forwarded-Host: awesome.byst.re' -H 'X-Forwarded-Proto: https' \
  -H 'X-Forwarded-Port: 443' "$base/v3/api-docs" | grep -q '"url":"https://awesome.byst.re"' \
  && echo "PASS OpenAPI server URL follows the gateway's forwarded headers" \
  || { echo "FAIL OpenAPI server URL ignores X-Forwarded-Proto" >&2; exit 1; }

CLIENT=$(token smoke-client ROLE_CLIENT)
ADMIN=$(token smoke-admin ROLE_ADMIN)
card() { printf '{"orderId":%s,"card":{"number":"%s","expiryMonth":12,"expiryYear":2035,"cvc":"987","holderName":"Smoke Test"}}' "$1" "$2"; }
pay() { local key=$1 body=$2; shift 2
  curl -s -w '\n%{http_code}' -X POST -H "Authorization: Bearer $CLIENT" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $key" "$@" -d "$body" -D "$WORK/headers" "$base/api/v1/payments"; }
corr_of() { docker exec "$RUN_ID-backend" grep "^CORR $1|" /tmp/requests.log 2>/dev/null | head -n1 || true; }
wait_corr() { local found=""; for _ in $(seq 1 20); do found=$(corr_of "$1"); [[ -n "$found" ]] && break; sleep 0.5; done
  printf '%s' "$found"; }

expect "missing token" "$(status_of "$base/api/v1/admin/payments")" 401
expect "client role forbidden on admin list" "$(status_of -H "Authorization: Bearer $CLIENT" "$base/api/v1/admin/payments")" 403
expect "tampered token" "$(status_of -H "Authorization: Bearer ${ADMIN}x" "$base/api/v1/admin/payments")" 401
expect "HMAC token signed with the public modulus" "$(status_of -H "Authorization: Bearer $(hmac_token)" "$base/api/v1/admin/payments")" 401

created=$(pay smoke-key-0001 "$(card 101 '4242 4242 4242 4242')" -H 'X-Request-Id: smoke-req-1' \
  -H "traceparent: 00-$TRACE_ID-$CALLER_SPAN-01")
grep -qi '^X-Request-Id: smoke-req-1' "$WORK/headers" && echo "PASS X-Request-Id answered" \
  || { echo "FAIL X-Request-Id not answered" >&2; exit 1; }
expect "visa payment created" "$(tail -n1 <<< "$created")" 201
contains "visa payment captured" "$created" '"status":"CAPTURED"'
contains "only brand and last4 returned" "$created" '"cardBrand":"VISA","cardLast4":"4242"'
payment_id=$(field id "$(head -n1 <<< "$created")")
replay=$(pay smoke-key-0001 "$(card 101 '4242 4242 4242 4242')")
expect "idempotent replay" "$(tail -n1 <<< "$replay")" 200
grep -qi '^Idempotent-Replayed: true' "$WORK/headers" && echo "PASS Idempotent-Replayed header" \
  || { echo "FAIL Idempotent-Replayed header missing" >&2; exit 1; }
expect "conflicting reuse of the key" "$(tail -n1 <<< "$(pay smoke-key-0001 "$(card 101 '5555 5555 5555 4444')")")" 409
expect "Luhn-invalid card" "$(tail -n1 <<< "$(pay smoke-key-0002 "$(card 102 '4242 4242 4242 4241')")")" 400
expired=$(pay smoke-key-0006 "$(card 106 '4242 4242 4242 4242')")
expect "expired reservation is not paid" "$(tail -n1 <<< "$expired")" 409
contains "expired reservation message" "$expired" '"message":"Order reservation expired"'

# The result notification reached the stub with the service token and a valid signature.
result=""
for _ in $(seq 1 20); do
  result=$(docker exec "$RUN_ID-backend" grep "^RESULT 101|" /tmp/requests.log 2>/dev/null | head -n1 || true)
  [[ -n "$result" ]] && break
  sleep 0.5
done
IFS='|' read -r _ result_token result_signature result_body <<< "$result"
expect "result notification carries the service token" "$result_token" "$SERVICE_TOKEN"
t=${result_signature#t=}; t=${t%%,*}
expected=$(printf '%s.%s' "$t" "$result_body" | openssl dgst -sha256 -hmac "$WEBHOOK_SECRET" -hex | awk '{print $NF}')
expect "result notification signature" "$result_signature" "t=$t,v1=$expected"
contains "result notification body" "$result_body" "\"paymentId\":\"$payment_id\",\"status\":\"CAPTURED\",\"amount\":42.50"
IFS='|' read -r _ corr_request corr_traceparent <<< "$(wait_corr 'POST /internal/v1/orders/101/payment')"
expect "result notification carries the X-Request-Id" "$corr_request" "smoke-req-1"
contains "result notification carries a child traceparent" "$corr_traceparent" "^00-$TRACE_ID-[0-9a-f]\{16\}-01$"
if [[ "$corr_traceparent" == *"$CALLER_SPAN"* ]]; then echo "FAIL child traceparent reuses the caller's span" >&2; exit 1; fi
invalid=$(pay smoke-key-0005 "$(card 105 '4242 4242 4242 4241')" -H 'X-Request-Id: bad id;x')
expect "rejected card with an invalid X-Request-Id" "$(tail -n1 <<< "$invalid")" 400
generated=$(sed -n 's/^[Xx]-[Rr]equest-[Ii]d: \([^[:space:]]*\).*/\1/p' "$WORK/headers")
contains "invalid X-Request-Id is replaced" "$generated" '^[0-9a-f-]\{36\}$'

challenged=$(pay smoke-key-0003 "$(card 103 '4000 0000 0000 3220')")
contains "3-D Secure challenge" "$challenged" '"type":"THREE_DS_CHALLENGE"'
challenge_id=$(field id "$(head -n1 <<< "$challenged")")
answered=$(curl -sf -X POST -H "Authorization: Bearer $CLIENT" -H 'Content-Type: application/json' -d '{"code":"123456"}' \
  "$base/api/v1/payments/$challenge_id/3ds")
contains "3-D Secure captured" "$answered" '"status":"CAPTURED"'

processing=$(pay smoke-key-0004 "$(card 104 '4000 0000 0000 0077')" -H 'X-Request-Id: smoke-async-1' \
  -H "traceparent: 00-$TRACE_ID-$CALLER_SPAN-01")
contains "processing card" "$processing" '"status":"PROCESSING"'
processing_id=$(field id "$(head -n1 <<< "$processing")")
settled=""
for _ in $(seq 1 20); do
  settled=$(curl -sf -H "Authorization: Bearer $CLIENT" "$base/api/v1/payments/$processing_id")
  grep -q '"status":"CAPTURED"' <<< "$settled" && break
  sleep 0.5
done
contains "scheduled settlement" "$settled" '"status":"CAPTURED"'
IFS='|' read -r _ corr_request corr_traceparent <<< "$(wait_corr 'POST /internal/v1/orders/104/payment')"
expect "settled notification carries the creating request's X-Request-Id" "$corr_request" "smoke-async-1"
contains "settled notification continues the creating request's trace" "$corr_traceparent" "^00-$TRACE_ID-"

listed=$(curl -sf -H "Authorization: Bearer $ADMIN" "$base/api/v1/admin/payments?status=CAPTURED")
contains "admin list" "$listed" '"totalElements":3'
order_payments=$(curl -sf -H "Authorization: Bearer $CLIENT" "$base/api/v1/orders/101/payments")
contains "order payments" "$order_payments" "\"id\":\"$payment_id\""

# Card refunds come from the backend event feed; the inbound refund endpoint is gone.
docker exec "$RUN_ID-backend" touch /tmp/publish-refund
expect "no inbound refund endpoint" "$(status_of -X POST -H "X-Service-Token: $SERVICE_TOKEN" \
  -H 'Content-Type: application/json' -d '{}' "$base/internal/v1/payments/refunds")" 401
refunded=""
for _ in $(seq 1 30); do
  refunded=$(curl -sf -H "Authorization: Bearer $CLIENT" "$base/api/v1/payments/$payment_id")
  grep -q '"status":"PARTIALLY_REFUNDED"' <<< "$refunded" && break
  sleep 0.5
done
contains "card refund from the event feed" "$refunded" '"status":"PARTIALLY_REFUNDED"'
contains "refunded amount" "$refunded" '"refundedAmount":12.50'
feed=""
for _ in $(seq 1 10); do
  feed=$(docker exec "$RUN_ID-backend" grep '^FEED .*after=1&' /tmp/requests.log 2>/dev/null | tail -n1 || true)
  [[ -n "$feed" ]] && break
  sleep 0.5
done
contains "feed cursor advanced past the refund event" "$feed" 'after=1&limit=100&'
contains "feed request asks only for card refunds" "$feed" 'types=card-refund.requested|'
contains "feed request carries the service token" "$feed" "|$SERVICE_TOKEN"
expect "other users' payments are not found" "$(status_of -H "Authorization: Bearer $(token smoke-other ROLE_CLIENT)" \
  "$base/api/v1/payments/$payment_id")" 404
expect "other users' orders are not found" "$(status_of -H "Authorization: Bearer $(token smoke-other ROLE_CLIENT)" \
  "$base/api/v1/orders/101/payments")" 404

app_log=$(docker logs "$RUN_ID-app" 2>&1)
payment_line=$(grep -m1 "\"message\":\"Payment $payment_id for order 101" <<< "$app_log" || true)
contains "JSON log line has the request id" "$payment_line" '"requestId":"smoke-req-1"'
contains "JSON log line has the trace id" "$payment_line" "\"traceId\":\"$TRACE_ID\""
contains "JSON log line has a span id" "$payment_line" '"spanId":"[0-9a-f]\{16\}"'
refund_line=$(grep -m1 '"message":"Refunded 12.50 of payment' <<< "$app_log" || true)
contains "card refund logs the cancel request's trace id" "$refund_line" "\"traceId\":\"$CANCEL_TRACE_ID\""
contains "card refund logs the cancel request's id" "$refund_line" '"requestId":"smoke-cancel-1"'
echo "JSON log line: $payment_line"
if grep -v '^{' <<< "$app_log" | grep -q .; then
  echo "FAIL non-JSON log lines with LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs:" >&2
  grep -v '^{' <<< "$app_log" | head -n5 >&2
  exit 1
fi
echo "PASS every log line is JSON"
if grep -E -q '4242424242424242|4242 4242 4242 4242|4000000000003220|4000000000000077|"987"' <<< "$app_log"; then
  echo "FAIL a card number reached the log" >&2
  exit 1
fi
echo "PASS no card numbers or CVC in the log"

if [[ "$TRACING" == 1 ]]; then
  exported=""
  for _ in $(seq 1 20); do
    exported=$(docker exec "$RUN_ID-backend" grep -c '^OTLP ' /tmp/requests.log 2>/dev/null || true)
    [[ "${exported:-0}" -gt 0 ]] && break
    sleep 0.5
  done
  [[ "${exported:-0}" -gt 0 ]] && echo "PASS spans exported over OTLP ($exported requests)" \
    || { echo "FAIL no OTLP export with TRACING_ENABLED=true" >&2; exit 1; }
else
  if docker exec "$RUN_ID-backend" grep -q '^OTLP ' /tmp/requests.log 2>/dev/null; then
    echo "FAIL spans exported with tracing off" >&2; exit 1
  fi
  echo "PASS nothing exported with tracing off"
fi

docker stats --no-stream --format 'memory: {{.MemUsage}}' "$RUN_ID-app"
echo "native smoke test passed"
