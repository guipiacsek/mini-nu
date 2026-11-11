#!/usr/bin/env bash
# A guided tour of the mini-nu API. Start the server, then:
#   ./demo.sh                        # against http://localhost:3000
#   ./demo.sh http://localhost:3001  # or any other address
# Needs curl and jq. Every step checks the status code, so CI runs this too.
set -euo pipefail

BASE=${1:-http://localhost:3000}
RUN=$(date +%s) # keeps idempotency keys unique across runs
command -v jq >/dev/null || { echo "demo.sh needs jq (brew install jq / apt install jq)"; exit 1; }
curl -sf "$BASE/accounts" >/dev/null || { echo "No mini-nu at $BASE. Start it with: java -jar mini-nu.jar"; exit 1; }

step() { printf '\n\033[1;35m%s\033[0m\n' "$*"; }
note() { printf '  \033[32m✓ %s\033[0m\n' "$*"; }

# call METHOD PATH EXPECTED-STATUS [BODY] [IDEMPOTENCY-KEY]  -> prints both sides, leaves the body in $OUT
call() {
  local method=$1 path=$2 want=$3 body=${4:-} key=${5:-} tmp status
  local args=(-s -X "$method" -H 'content-type: application/json' -H 'X-Actor: demo.sh' -w '%{http_code}')
  [[ -n $key ]] && args+=(-H "Idempotency-Key: $key")
  [[ -n $body ]] && args+=(-d "$body")
  tmp=$(mktemp)
  status=$(curl "${args[@]}" -o "$tmp" "$BASE$path")
  OUT=$(cat "$tmp"); rm -f "$tmp"
  printf '  %s %s%s\n' "$method" "$path" "${key:+   [Idempotency-Key: $key]}"
  [[ -n $body ]] && printf '  → %s\n' "$body"
  printf '  ← %s %s\n' "$status" "$(jq -c . <<<"$OUT" 2>/dev/null | cut -c1-200)"
  [[ $status == "$want" ]] || { printf '\033[31m✗ expected %s\033[0m\n' "$want" >&2; exit 1; }
}

step "1. Open two accounts: Gui in the U.S. (USD) and Mom in Brazil (BRL)"
call POST /accounts 201 '{"owner":"Gui","currency":"USD"}'; GUI=$(jq -r .id <<<"$OUT")
call POST /accounts 201 '{"owner":"Mom","currency":"BRL"}'; MOM=$(jq -r .id <<<"$OUT")

step "2. Deposit \$2,500 into Gui's account (amounts are integer cents)"
call POST /deposits 201 "{\"to\":\"$GUI\",\"amount\":250000}" "dep-$RUN"
sleep 1 # timestamps below are whole seconds, so step clear of the deposit
BEFORE=$(date -u +%Y-%m-%dT%H:%M:%SZ)
sleep 1

step "3. Send \$200 to Mom. It converts at the current rate, which is stored on the transfer"
SEND="{\"from\":\"$GUI\",\"to\":\"$MOM\",\"amount\":20000}"
call POST /transfers 201 "$SEND" "send-$RUN"; T=$(jq -r .id <<<"$OUT")
note "Mom received R\$ $(jq -r '.credited / 100' <<<"$OUT") at $(jq -r '."fx-rate"' <<<"$OUT") BRL per USD"

step "4. The phone lost signal, so the app retries with the same key"
call POST /transfers 201 "$SEND" "send-$RUN"
[[ $(jq -r .id <<<"$OUT") == "$T" ]] && note "same transfer returned: Gui was charged once"

step "5. A different amount under a used key is refused"
call POST /transfers 409 "{\"from\":\"$GUI\",\"to\":\"$MOM\",\"amount\":99900}" "send-$RUN"

step "6. Sending more than the balance breaks a rule (422), and nothing moves"
call POST /transfers 422 "{\"from\":\"$GUI\",\"to\":\"$MOM\",\"amount\":10000000}" "big-$RUN"

step "7. A malformed request is a 400 that names each bad field"
call POST /transfers 400 "{\"from\":\"$GUI\",\"to\":\"not-an-id\",\"amount\":\"lots\"}" "bad-$RUN"

step "8. Time travel: Gui's balance now, and before the transfer"
call GET "/accounts/$GUI/balance" 200
call GET "/accounts/$GUI/balance?as-of=$BEFORE" 200
[[ $(jq .balance <<<"$OUT") == 250000 ]] || { echo "expected the \$2,500 from before the transfer" >&2; exit 1; }
note "\$2,500 back then: Datomic never overwrites, so any past balance can be read back"

step "9. Reverse the transfer. Nothing is deleted; the opposite entries are added"
call POST "/transfers/$T/reversal" 201; R=$(jq -r .id <<<"$OUT")
call POST "/transfers/$T/reversal" 201
[[ $(jq -r .id <<<"$OUT") == "$R" ]] && note "reversing twice returns the same reversal"

step "10. Transfer limits are lower at night (20:00-06:00 local), like Brazil's Pix"
call GET "/accounts/$GUI/limit" 200
note "$(jq -r '"\(.period) limit: \(.remaining / 100) of \(.limit / 100) left"' <<<"$OUT")"

step "11. Open a credit card, buy things, and pay the bill from checking"
call POST "/accounts/$GUI/card" 201 '{"credit-limit":150000,"closing-day":5}'; CARD=$(jq -r .id <<<"$OUT")
call POST "/cards/$CARD/purchases" 201 '{"amount":8420,"merchant":"Publix"}' "buy1-$RUN"
call POST "/cards/$CARD/purchases" 201 '{"amount":2375,"merchant":"Uber"}' "buy2-$RUN"
call POST "/cards/$CARD/payments" 201 '{"amount":5000}' "pay-$RUN"
call GET "/cards/$CARD/bill" 200
note "$(jq -r '"owed \(.owed / 100), available \(.available / 100)"' <<<"$OUT")"

step "12. Schedule \$100 to Mom every month"
call POST /schedules 201 "{\"from\":\"$GUI\",\"to\":\"$MOM\",\"amount\":10000,\"every\":\"month\"}"

step "13. Gui's statement, newest first"
call GET "/accounts/$GUI/statement?limit=5" 200
jq -r '.items[] | "    \(.at[0:19])  \(.kind | .[0:12] | . + " " * (12 - length))  \(.amount / 100)  \(.counterparty)"' <<<"$OUT"

step "14. Metrics"
call GET /metrics 200
note "$(jq -r '"\(.ledger.transfers) transfers, p50 latency \(."latency-ms".p50) ms"' <<<"$OUT")"

printf '\n\033[1mDone.\033[0m The UI is at %s and every endpoint is documented at %s/api-docs\n' "$BASE" "$BASE"
