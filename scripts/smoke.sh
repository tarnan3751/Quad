#!/usr/bin/env bash
# Curl-based smoke test against a running server (default http://localhost:8080).
#
#   scripts/smoke.sh [base-url]
#
# Checks health always. If the demo data is seeded and the auth/listing endpoints exist, also logs in as a
# seeded account and runs a listing search and a categories call. Fails loudly (non-zero exit) on any problem.
# Env: BASE_URL, SMOKE_EMAIL (default maya@state.edu), SMOKE_PASSWORD (default password123).
set -uo pipefail

BASE="${1:-${BASE_URL:-http://localhost:8080}}"
BASE="${BASE%/}"
API="$BASE/api/v1"
EMAIL="${SMOKE_EMAIL:-maya@state.edu}"
PASSWORD="${SMOKE_PASSWORD:-password123}"

fail() { echo "SMOKE FAIL: $*" >&2; exit 1; }
ok() { echo "ok   $*"; }

# request METHOD PATH [curl args...] -> sets STATUS and BODY
request() {
  local method="$1" path="$2"; shift 2
  local out
  out=$(curl -sS -m 15 -X "$method" -w $'\n%{http_code}' "$@" "$API$path") || fail "$method $path: could not connect to $BASE"
  STATUS="${out##*$'\n'}"
  BODY="${out%$'\n'*}"
}

request GET /health
[ "$STATUS" = 200 ] || fail "GET /health returned $STATUS: $BODY"
ok "GET /health -> 200"

request POST /auth/login -H 'Content-Type: application/json' -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}"
case "$STATUS" in
  404|405)
    echo "skip auth/listings checks: login endpoint not available yet ($STATUS)"
    echo "SMOKE OK (health only)"; exit 0 ;;
  200) ;;
  *) fail "POST /auth/login as $EMAIL returned $STATUS: $BODY (is the database seeded? run ./gradlew :server:seed)" ;;
esac
TOKEN=$(printf '%s' "$BODY" | sed -n 's/.*"accessToken" *: *"\([^"]*\)".*/\1/p')
[ -n "$TOKEN" ] || fail "login response had no accessToken: $BODY"
ok "POST /auth/login as $EMAIL -> 200"

AUTH=(-H "Authorization: Bearer $TOKEN")

request GET /me "${AUTH[@]}"
[ "$STATUS" = 200 ] || fail "GET /me returned $STATUS: $BODY"
ok "GET /me -> 200"

request GET "/listings?q=bike&limit=5" "${AUTH[@]}"
case "$STATUS" in
  404|405) echo "skip listing checks: listings endpoint not available yet ($STATUS)"; echo "SMOKE OK (partial)"; exit 0 ;;
  200) ;;
  *) fail "GET /listings?q=bike returned $STATUS: $BODY" ;;
esac
printf '%s' "$BODY" | grep -q '"items"' || fail "listing search response has no items array: $BODY"
printf '%s' "$BODY" | grep -qi 'bike' || fail "listing search for 'bike' found nothing (seed data missing?): $BODY"
ok "GET /listings?q=bike -> 200 with results"

request GET /listings "${AUTH[@]}"
[ "$STATUS" = 200 ] || fail "GET /listings returned $STATUS: $BODY"
ok "GET /listings -> 200"

request GET /categories "${AUTH[@]}"
[ "$STATUS" = 200 ] || fail "GET /categories returned $STATUS: $BODY"
ok "GET /categories -> 200"

echo "SMOKE OK"
