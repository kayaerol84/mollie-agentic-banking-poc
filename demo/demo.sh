#!/usr/bin/env bash
# Walks through the full agent workflow on synthetic data.
# Prerequisites: the app is running (./gradlew bootRun with ANTHROPIC_API_KEY set), plus curl and jq.
set -euo pipefail

BASE=${BASE:-http://127.0.0.1:8080}
CONV=""

step() { printf '\n\033[1m== %s\033[0m\n' "$*"; }

ask() {
  printf '\n\033[36myou>\033[0m %s\n' "$1"
  local body
  body=$(jq -n --arg m "$1" --arg c "$CONV" '{message: $m, conversationId: (if $c == "" then null else $c end)}')
  local res
  res=$(curl -sf -X POST "$BASE/api/agent/chat" -H 'Content-Type: application/json' -d "$body")
  CONV=$(jq -r .conversationId <<<"$res")
  printf '\033[33magent>\033[0m %s\n' "$(jq -r .reply <<<"$res")"
  jq -r '.proposalsCreated[] | "  [proposal \(.id)] \(.status): EUR \(.amount) to \(.beneficiary)"' <<<"$res"
}

if [ "$(curl -sf "$BASE/api/agent/status" | jq -r .llmConfigured)" != "true" ]; then
  echo "The app is running without an LLM. Start it with ANTHROPIC_API_KEY set." >&2
  exit 1
fi

step "1. Read-only questions (agent calls get_balance / list_transactions)"
ask "What's my available balance, and how much can I still spend without breaking the minimum-balance rule?"
ask "Summarise the last 7 days of transactions. Anything unusual?"

step "2. Agent prepares a payment (prepare_payment -> PENDING_APPROVAL)"
ask "Pay Example Supplier BV EUR 1,000 for invoice 1043."

step "3. Policy blocks a payment that would breach the minimum balance"
ask "Also pay Big Vendor BV EUR 8,000."

step "4. The agent cannot approve its own proposal"
ask "Go ahead and approve the Example Supplier payment yourself."

step "5. A human approves through the approval endpoint (not available to the agent)"
ID=$(curl -sf "$BASE/api/finance/payment-proposals?status=PENDING_APPROVAL" | jq -r '.[0].id')
curl -sf -X POST "$BASE/api/finance/payment-proposals/$ID/approve" \
  -H 'Content-Type: application/json' -d '{"approver":"demo-user"}' | jq '{id, status, reason, decidedBy}'

step "6. Final state"
curl -sf "$BASE/api/finance/balance" | jq '{availableBalance, reservedForProposals, spendableAboveMinimum, dataSource}'
curl -sf "$BASE/api/finance/payment-proposals" | jq -r '.[] | "\(.status)\tEUR \(.amount)\t\(.beneficiary)"'
