# Isolation lab

Run `docker compose up --build`, then connect two psql sessions:

```bash
docker compose exec db psql -U wallet -d wallet
```

## Read Committed
Session A:
```sql
BEGIN ISOLATION LEVEL READ COMMITTED;
SELECT balance_paise FROM accounts WHERE id=1;
```
Session B:
```sql
UPDATE accounts SET balance_paise=balance_paise+100 WHERE id=1;
COMMIT;
```
Session A repeats the SELECT and can see B's committed value because Read Committed takes a fresh snapshot per statement. Then `ROLLBACK` A.

## Repeatable Read
Session A:
```sql
BEGIN ISOLATION LEVEL REPEATABLE READ;
SELECT balance_paise FROM accounts WHERE id=1;
```
Session B updates and commits as above. A repeats the SELECT and sees its transaction snapshot. `ROLLBACK` A.

## Serializable
Start two transactions that both read a condition and write based on it. PostgreSQL may abort one with SQLSTATE `40001`; the caller must retry the *whole transaction* with a bounded retry policy.

## Transfer strategy
The service uses a database transaction plus `SELECT ... FOR UPDATE` on account rows in ascending account ID order, then re-reads the source balance while holding the lock, writes transfer + ledger rows, updates both cached balances and commits. Locks serialize competing writes to the same account. Deterministic lock order reduces deadlocks. Read Committed plus explicit row locks is appropriate for this narrow invariant; Serializable adds abort/retry overhead and is not a substitute for correct locking.

## Limitations to test before claiming production readiness
- The starter has not yet implemented all brief requirements, especially full idempotency coverage for top-up/withdrawal, fraud decision/hold/release workflows, reversal endpoint, complete audit-state enforcement, large-scale seed/load tests and the full acceptance suite.
- A database trigger or restricted DB role should enforce ledger append-only behavior against direct SQL mutations; the starter documents the invariant but does not yet install that trigger.
