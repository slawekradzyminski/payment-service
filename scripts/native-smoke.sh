#!/usr/bin/env bash
# Boots the payment-service image against a real Postgres and checks that the
# native binary migrates the schema, validates backend-style RS256 JWTs against
# a JWKS, takes card payments (JPA writes, outbox, signed result notification,
# scheduled settlement), books card refunds from the backend event feed and
# serves health, metrics and OpenAPI. Instead of a backend, a busybox nc stub
# publishes the JWKS at /internal/v1/jwks.json, answers GET /internal/v1/orders/{id}
# with a PENDING order of "smoke-client", records POST /internal/v1/orders/{id}/payment
# and serves GET /internal/v1/events: one card-refund.requested event for order 101
# once the script publishes it (touch /tmp/publish-refund in the stub).
#   scripts/native-smoke.sh payment-service:native
set -euo pipefail

IMAGE="${1:?usage: native-smoke.sh IMAGE}"
BUSYBOX_IMAGE="busybox:1.37.0@sha256:a7dbc319b1438115a207e11930dc4bb3e643dcb3ec0bdd694bb36cd8fad289ec"
POSTGRES_IMAGE="postgres:16-alpine@sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777"
RUN_ID="payment-smoke-$$"
NETWORK="$RUN_ID"
PORT="${SMOKE_PORT:-14004}"
SERVICE_TOKEN="native-smoke-service-token"
WEBHOOK_SECRET="native-smoke-webhook-secret"

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
while IFS= read -r line; do
  line=$(printf '%s' "$line" | tr -d '\r')
  [ -z "$line" ] && break
  case "$line" in
    [Cc]ontent-[Ll]ength:*) length=${line#*: } ;;
    [Xx]-[Pp]ayment-[Ss]ignature:*) signature=${line#*: } ;;
    [Xx]-[Ss]ervice-[Tt]oken:*) token=${line#*: } ;;
  esac
done
body=""
[ "$length" -gt 0 ] && body=$(head -c "$length")
status="200 OK"
case "$method $path" in
  "GET /internal/v1/jwks.json") response=$(cat /srv/jwks.json) ;;
  "GET /internal/v1/orders/"*)
    id=${path##*/}
    response=$(printf '{"id":%s,"username":"smoke-client","status":"PENDING","totalAmount":42.50,"amountDue":42.50,"items":[]}' "$id") ;;
  "GET /internal/v1/events?"*)
    printf 'FEED %s|%s\n' "$path" "$token" >> /tmp/requests.log
    after=$(printf '%s' "$path" | sed -n 's/.*[?&]after=\([0-9]*\).*/\1/p')
    if [ "${after:-0}" = 0 ] && [ -f /tmp/publish-refund ]; then
      response='{"events":[{"sequence":1,"id":"00000000-0000-0000-0000-0000000000e1","type":"card-refund.requested","occurredAt":"2026-11-10T12:00:00Z","orderId":101,"username":"smoke-client","data":{"requestId":"00000000-0000-0000-0000-00000000a001","orderId":101,"amount":12.50,"reason":"smoke"}}],"nextCursor":1,"hasMore":false}'
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

started=$(date +%s)
docker run -d --name "$RUN_ID-app" --network "$NETWORK" --memory 160m -p "$PORT:4004" \
  -e SPRING_PROFILES_ACTIVE=docker \
  -e SPRING_DATASOURCE_URL="jdbc:postgresql://$RUN_ID-db:5432/payment?currentSchema=payment" \
  -e SPRING_DATASOURCE_USERNAME=payment -e SPRING_DATASOURCE_PASSWORD=payment \
  -e PAYMENT_SERVICE_TOKEN="$SERVICE_TOKEN" -e PAYMENT_WEBHOOK_SECRET="$WEBHOOK_SECRET" \
  -e PAYMENT_SETTLEMENT_DELAY=PT2S -e PAYMENT_SETTLEMENT_INTERVAL=PT1S -e PAYMENT_EVENTS_POLL_INTERVAL=PT1S \
  -e BACKEND_BASE_URL="http://$RUN_ID-backend:4001" \
  "$IMAGE" >/dev/null

base="http://localhost:$PORT"
for _ in $(seq 1 90); do
  [[ "$(status_of "$base/actuator/health")" == 200 ]] && break
  sleep 1
done
expect "health" "$(status_of "$base/actuator/health")" 200
echo "ready after $(( $(date +%s) - started ))s"

expect "prometheus metrics" "$(status_of "$base/actuator/prometheus")" 200
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
pay() { curl -s -w '\n%{http_code}' -X POST -H "Authorization: Bearer $CLIENT" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $1" -d "$2" -D "$WORK/headers" "$base/api/v1/payments"; }

expect "missing token" "$(status_of "$base/api/v1/admin/payments")" 401
expect "client role forbidden on admin list" "$(status_of -H "Authorization: Bearer $CLIENT" "$base/api/v1/admin/payments")" 403
expect "tampered token" "$(status_of -H "Authorization: Bearer ${ADMIN}x" "$base/api/v1/admin/payments")" 401
expect "HMAC token signed with the public modulus" "$(status_of -H "Authorization: Bearer $(hmac_token)" "$base/api/v1/admin/payments")" 401

created=$(pay smoke-key-0001 "$(card 101 '4242 4242 4242 4242')")
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

challenged=$(pay smoke-key-0003 "$(card 103 '4000 0000 0000 3220')")
contains "3-D Secure challenge" "$challenged" '"type":"THREE_DS_CHALLENGE"'
challenge_id=$(field id "$(head -n1 <<< "$challenged")")
answered=$(curl -sf -X POST -H "Authorization: Bearer $CLIENT" -H 'Content-Type: application/json' -d '{"code":"123456"}' \
  "$base/api/v1/payments/$challenge_id/3ds")
contains "3-D Secure captured" "$answered" '"status":"CAPTURED"'

processing=$(pay smoke-key-0004 "$(card 104 '4000 0000 0000 0077')")
contains "processing card" "$processing" '"status":"PROCESSING"'
processing_id=$(field id "$(head -n1 <<< "$processing")")
settled=""
for _ in $(seq 1 20); do
  settled=$(curl -sf -H "Authorization: Bearer $CLIENT" "$base/api/v1/payments/$processing_id")
  grep -q '"status":"CAPTURED"' <<< "$settled" && break
  sleep 0.5
done
contains "scheduled settlement" "$settled" '"status":"CAPTURED"'

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

if docker logs "$RUN_ID-app" 2>&1 | grep -E -q '4242424242424242|4242 4242 4242 4242|4000000000003220'; then
  echo "FAIL a card number reached the log" >&2
  exit 1
fi
echo "PASS no card numbers in the log"

docker stats --no-stream --format 'memory: {{.MemUsage}}' "$RUN_ID-app"
echo "native smoke test passed"
