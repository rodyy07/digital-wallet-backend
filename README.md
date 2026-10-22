# Digital Wallet Backend

A learning/portfolio starter for a digital wallet backend using Java 21, Spring Boot, PostgreSQL, JDBC and Flyway. The design emphasizes visible SQL, integer paise, double-entry ledger entries, row locks, and reconciliation.

> **Status: starter implementation, not a production payment system.** Read the Known gaps section before presenting this as complete. It is not connected to a bank, UPI rail, or real KYC provider.

## Run
Prerequisites: Docker Desktop / Docker Engine and Docker Compose.

```bash
docker compose up --build
```
API: `http://localhost:8080`. PostgreSQL is exposed on `localhost:5432` for local development. Development credentials are intentionally simple and must never be reused in production.

## Example flow
Create a user:
```bash
curl -X POST http://localhost:8080/users -H 'Content-Type: application/json' \
  -d '{"name":"Asha","email":"asha@example.com","phone":"9876543210"}'
```
Open a wallet (replace user ID):
```bash
curl -X POST http://localhost:8080/users/1/wallets -H 'Content-Type: application/json' \
  -d '{"name":"Main wallet"}'
```
Balance:
```bash
curl http://localhost:8080/wallets/1/balance
```
Transfer (both wallets must exist and have funds):
```bash
curl -X POST http://localhost:8080/transfers \
  -H 'Content-Type: application/json' -H 'X-User-Id: 1' -H 'Idempotency-Key: demo-transfer-001' \
  -d '{"sourceAccountId":1,"destinationAccountId":2,"amountPaise":1000,"note":"Lunch"}'
```
Statement:
```bash
curl 'http://localhost:8080/wallets/1/statement?limit=20'
```
Run reconciliation:
```bash
curl -X POST http://localhost:8080/admin/reconciliation/run
```

## Important implementation notes
- `amountPaise` is a positive integer; ₹10 is `1000` paise.
- The peer-transfer path locks both account rows in ascending ID order using explicit SQL `FOR UPDATE`.
- Transfer rows, ledger rows, cached balances and the idempotency response are written in a Spring transaction.
- Flyway builds the schema from an empty database.
- The statement Q&A implementation is a deterministic narrow parser, not a live LLM integration. It does not allow model-generated SQL. Extend it only behind a typed intent schema and caller-account scope.
- API identity uses `X-User-Id` for local demonstration only. Add authentication and authorization before any shared deployment.

## Docs
- [`docs/schema.md`](docs/schema.md): tables, cardinality, indexes and ER diagram.
- [`docs/isolation.md`](docs/isolation.md): isolation-level lab and transfer locking rationale.

## Known gaps before production / full assignment acceptance
This repository is a useful foundation, but it does **not** yet satisfy every line of the supplied assignment. In particular:
1. Top-up/withdrawal currently do not use the same complete idempotency protocol as peer transfers; implement this before considering retry safety complete.
2. System accounts must be seeded before using top-up/withdrawal. Add an idempotent seed profile or migration for them.
3. Fraud velocity/amount/new-payee rules are schema-only; held transfer release/reversal/admin queue is not implemented.
4. Reversal endpoint, immutable-ledger database trigger, transfer status transition enforcement, and full status audit are not implemented.
5. Reconciliation covers transfer balancing, account cached balance mismatch, and missing ledger rows; it does not yet check every global invariant from the brief.
6. The assignment's 100-request stampede test, full idempotency parallel test, 10,000-transfer corruption test, load tests and 1-million-row `EXPLAIN ANALYZE` report are not included yet.
7. Cursor pagination in this starter uses `beforeId` rather than the requested `(created_at,id)` cursor.
8. Security hardening, rate limits, authentication, secrets management, production observability, and real provider integrations remain future work.

## Suggested next steps
1. Add integration tests with Testcontainers for PostgreSQL.
2. Finish idempotency for every POST, including concurrent duplicate keys.
3. Implement fraud rules inside the transfer transaction, held-transfer admin review and reversal.
4. Add database triggers preventing ledger UPDATE/DELETE and enforce allowed status transitions.
5. Add complete reconciliation checks, seed data, performance evidence and CI.
