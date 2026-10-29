# Intelligent Core POC — Kotlin

A small, runnable first vertical slice for an agentic financial execution platform.

## What it does today

- Exposes synthetic account balance and transaction data.
- Accepts a payment proposal.
- Enforces a deterministic €5,000 minimum-balance rule.
- Requires explicit approval in the workflow: this scaffold does **not** execute or move money.
- Returns the same proposal for repeated idempotency keys.
- Includes tests for policy enforcement and idempotency.

## Requirements

- JDK 21
- Gradle (or add the Gradle wrapper with `gradle wrapper`)

## Run

```bash
gradle bootRun
```

Then try:

```bash
curl http://localhost:8080/api/finance/balance
curl http://localhost:8080/api/finance/transactions
curl -X POST http://localhost:8080/api/finance/payment-proposals \
  -H 'Content-Type: application/json' \
  -d '{
    "beneficiary": "Example Supplier BV",
    "amount": 1000.00,
    "currency": "EUR",
    "idempotencyKey": "demo-payment-001"
  }'
```

Try an amount of `8000.00` to see the minimum-balance policy reject the proposal.

## Architecture and next steps

1. Keep `FinanceService` as the deterministic domain/policy layer.
2. Add persistence for proposals, approvals, and execution events.
3. Add Spring AI tool calling so the LLM can request balance/transaction reads and prepare proposals.
4. Expose selected capabilities through Spring AI's MCP server starter (`@McpTool`), after deciding whether this application should be an MCP server, an MCP client, or both.
5. Implement an explicit approval endpoint and a sandbox adapter.
6. Add authentication/authorization before exposing any network-accessible MCP endpoint.

## Safety note

This is a demo scaffold with in-memory synthetic data. It is not production-ready, is not connected to a bank, and cannot execute payments. Do not add live banking credentials to this project.
