# Mollie Agentic Banking POC

A small, runnable slice of an agentic financial execution platform, built with Kotlin, Spring Boot 4 and Spring AI 2.

An LLM agent (Claude) can read a business account and **prepare** payments. A deterministic policy layer decides what is allowed, and only a human can approve. The same three tools are exposed to the in-app agent and, over MCP, to any external AI client. Bank data comes from synthetic fixtures by default or from the **TrueLayer sandbox** Data API.

> Demo scaffold. No real bank connection, no live credentials, and nothing in this codebase can execute a payment.

## What's in it

| Capability | Where |
|---|---|
| Deterministic policy: minimum balance, reservations for pending proposals, idempotency, human-only approval | `domain/FinanceService.kt` |
| Agent tools `get_balance`, `list_transactions`, `prepare_payment` (no approve/execute tool exists) | `agent/FinanceTools.kt` |
| Claude agent via Spring AI `ChatClient`, with conversation memory | `agent/FinanceAgent.kt` |
| MCP server (Streamable HTTP at `/mcp`) exposing the same tools | Spring AI MCP server starter, `application.yml` |
| Demo page: chat with the agent, then approve or decline its proposals | `src/main/resources/static/index.html` |
| Bank data port with two adapters: synthetic and TrueLayer sandbox | `domain/AccountDataProvider.kt`, `adapter/` |

```
 Browser demo page ─┐                            ┌─ SyntheticAccountDataProvider (default)
 REST clients ──────┼─> FinanceService (policy) ─┤
 Claude agent ──────┤        ▲                   └─ TrueLayerAccountDataProvider (sandbox)
 MCP clients ───────┘        │
        └── FinanceTools: get_balance · list_transactions · prepare_payment
            (approve / decline are REST-only, for humans)
```

## Requirements

- JDK 21
- An Anthropic API key for the agent. Without one, the REST API, MCP server and demo page still work; only `/api/agent/chat` is disabled.

## Run

```bash
export ANTHROPIC_API_KEY=sk-ant-...      # optional: enables the agent
./gradlew bootRun
```

Open http://localhost:8080 for the demo page. The server listens on `127.0.0.1` only, because none of the endpoints are authenticated yet.

| Variable | Default | Purpose |
|---|---|---|
| `ANTHROPIC_API_KEY` | none | Enables the Claude agent |
| `ANTHROPIC_MODEL` | `claude-sonnet-5-5` | Model id |
| `FINANCE_DATA_PROVIDER` | `synthetic` | `synthetic` or `truelayer` |
| `FINANCE_MINIMUM_BALANCE` | `5000.00` | Policy floor, in the account currency |
| `TRUELAYER_CLIENT_ID` / `TRUELAYER_CLIENT_SECRET` | none | Sandbox credentials, required for `truelayer` |
| `TRUELAYER_REDIRECT_URI` | `http://localhost:8080/truelayer/callback` | Must be allowed in TrueLayer Console |

## Demo: the full workflow on synthetic data

With the app running and an API key set:

```bash
./demo/demo.sh     # needs curl and jq
```

The script walks through this flow, which you can also click through on the demo page:

1. **Read.** "What's my balance and how much can I still spend?" The agent calls `get_balance`.
2. **Analyse.** "Summarise the last 7 days of transactions." The agent calls `list_transactions`.
3. **Prepare.** "Pay Example Supplier BV €1,000 for invoice 1043." The agent calls `prepare_payment` and gets `PENDING_APPROVAL`. €1,000 is now reserved; no money moves.
4. **Policy.** "Also pay Big Vendor BV €8,000." The proposal comes back `REJECTED_POLICY` because it would breach the €5,000 minimum, counting the €1,000 already reserved. Splitting it into smaller payments doesn't get around this.
5. **Boundary.** "Approve it yourself." The agent has no tool for that, and its instructions tell it to say so.
6. **Human decision.** `POST /api/finance/payment-proposals/{id}/approve`, or the Approve button on the page.

REST endpoints, without the agent:

```bash
curl localhost:8080/api/finance/balance
curl "localhost:8080/api/finance/transactions?from=2026-10-01&limit=5"
curl -X POST localhost:8080/api/finance/payment-proposals -H 'Content-Type: application/json' \
  -d '{"beneficiary":"Example Supplier BV","amount":1000.00,"idempotencyKey":"demo-1","reference":"Invoice 1043"}'
curl localhost:8080/api/finance/payment-proposals?status=PENDING_APPROVAL
curl -X POST localhost:8080/api/finance/payment-proposals/{id}/approve -H 'Content-Type: application/json' -d '{"approver":"you"}'
```

## Use the tools from Claude Code or Claude Desktop (MCP)

The app is an MCP server at `http://127.0.0.1:8080/mcp` (Streamable HTTP). It doesn't need an Anthropic key, because the external client brings its own model.

```bash
claude mcp add --transport http banking-poc http://127.0.0.1:8080/mcp
```

For Claude Desktop or other clients that take JSON config:

```json
{ "mcpServers": { "banking-poc": { "type": "http", "url": "http://127.0.0.1:8080/mcp" } } }
```

Proposals created over MCP show up on the demo page for approval like any other.

## Switch to the TrueLayer sandbox

1. In [TrueLayer Console](https://console.truelayer.com), switch **Live** off (sandbox). Copy the client ID and secret, and add `http://localhost:8080/truelayer/callback` to the allowed redirect URIs.
2. Run:
   ```bash
   export FINANCE_DATA_PROVIDER=truelayer
   export TRUELAYER_CLIENT_ID=sandbox-...
   export TRUELAYER_CLIENT_SECRET=...
   export FINANCE_MINIMUM_BALANCE=500      # Mock Bank balances are smaller than the synthetic ones
   ./gradlew bootRun
   ```
3. Open http://localhost:8080/truelayer/connect, choose **Mock Bank** and log in with `john` / `doe`.
4. The agent, demo page and MCP tools now read real sandbox data through the Data API (`/data/v1/accounts`, `/balance`, `/transactions`). Tokens refresh automatically and are held in memory, so a restart means reconnecting.

Mock Bank accounts are UK accounts in **GBP**, so proposals follow the account currency. The policy and the tools don't change.

## Tests

```bash
./gradlew test
```

The suite runs without network access or API keys:

- **Policy and tools:** unit tests drive the tools through Spring AI's own JSON-schema tool callbacks.
- **Agent workflow:** a scripted model stands in for Claude, while Spring AI's real tool-calling loop, chat memory and policy all run.
- **MCP:** a real MCP client connects over Streamable HTTP, lists the tools and calls them.
- **REST:** the full human-in-the-loop flow, including the error mappings.
- **TrueLayer:** the adapter runs against an in-process fake of TrueLayer's auth server and Data API: connect flow, state check, token refresh and field mapping.
- **Live Claude:** a test against the real API runs only when `ANTHROPIC_API_KEY` is set. In CI, add it as a repository secret.

## Not done yet

- **Executing approved payments.** Create a TrueLayer Payments API v3 payment from an approved proposal and send the approver to the hosted payment page. This needs request signing keys and a sandbox merchant account.
- **Authentication and authorisation** for the REST, approval and MCP endpoints (Spring Security; step-up auth for approvals).
- **Persistence** for proposals, decisions and an audit log of every tool call.
- **Per-user bank connections** with encrypted token storage.
