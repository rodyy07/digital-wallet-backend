CREATE TABLE users (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  name VARCHAR(120) NOT NULL,
  email VARCHAR(254) NOT NULL UNIQUE,
  phone VARCHAR(30) NOT NULL UNIQUE,
  kyc_status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
    CHECK (kyc_status IN ('PENDING','VERIFIED','REJECTED')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE accounts (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id BIGINT REFERENCES users(id),
  name VARCHAR(80) NOT NULL,
  account_type VARCHAR(20) NOT NULL CHECK (account_type IN ('WALLET','CASH_IN','CASH_OUT','FEE_REVENUE')),
  currency CHAR(3) NOT NULL DEFAULT 'INR' CHECK (currency = 'INR'),
  balance_paise BIGINT NOT NULL DEFAULT 0,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK (account_type <> 'WALLET' OR user_id IS NOT NULL),
  CHECK (account_type = 'WALLET' OR user_id IS NULL),
  CHECK (account_type <> 'WALLET' OR balance_paise >= 0)
);
CREATE INDEX idx_accounts_user ON accounts(user_id) WHERE user_id IS NOT NULL;

CREATE TABLE transfers (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  transfer_type VARCHAR(20) NOT NULL CHECK (transfer_type IN ('TOPUP','PEER','WITHDRAWAL','FEE','REVERSAL')),
  status VARCHAR(20) NOT NULL CHECK (status IN ('COMPLETED','HELD','REJECTED','REVERSED')),
  amount_paise BIGINT NOT NULL CHECK (amount_paise > 0),
  source_account_id BIGINT REFERENCES accounts(id),
  destination_account_id BIGINT REFERENCES accounts(id),
  reversal_of_id BIGINT UNIQUE REFERENCES transfers(id),
  note TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK (source_account_id IS NULL OR destination_account_id IS NULL OR source_account_id <> destination_account_id)
);
CREATE INDEX idx_transfers_created ON transfers(created_at DESC, id DESC);
CREATE INDEX idx_transfers_source_created ON transfers(source_account_id, created_at DESC, id DESC);
CREATE INDEX idx_transfers_destination_created ON transfers(destination_account_id, created_at DESC, id DESC);

CREATE TABLE ledger_entries (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  transfer_id BIGINT NOT NULL REFERENCES transfers(id),
  account_id BIGINT NOT NULL REFERENCES accounts(id),
  direction VARCHAR(6) NOT NULL CHECK (direction IN ('DEBIT','CREDIT')),
  amount_paise BIGINT NOT NULL CHECK (amount_paise > 0),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ledger_account_created ON ledger_entries(account_id, created_at DESC, id DESC);
CREATE INDEX idx_ledger_transfer ON ledger_entries(transfer_id);

CREATE TABLE idempotency_keys (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES users(id),
  idem_key VARCHAR(200) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  response_status INTEGER,
  response_body JSONB,
  state VARCHAR(16) NOT NULL DEFAULT 'IN_PROGRESS' CHECK (state IN ('IN_PROGRESS','COMPLETED')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(user_id, idem_key)
);
CREATE INDEX idx_idempotency_created ON idempotency_keys(created_at);

CREATE TABLE transfer_audit_logs (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  transfer_id BIGINT NOT NULL REFERENCES transfers(id),
  old_status VARCHAR(20),
  new_status VARCHAR(20) NOT NULL,
  changed_by VARCHAR(120) NOT NULL,
  reason TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_transfer_audit_transfer ON transfer_audit_logs(transfer_id, created_at);

CREATE TABLE risk_rules (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  rule_code VARCHAR(40) NOT NULL UNIQUE,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  threshold BIGINT NOT NULL,
  action VARCHAR(10) NOT NULL CHECK (action IN ('HOLD','FLAG')),
  description TEXT NOT NULL
);
INSERT INTO risk_rules(rule_code, threshold, action, description) VALUES
 ('VELOCITY', 5, 'HOLD', 'Hold when more than five outgoing transfers occur in ten minutes'),
 ('NEW_PAYEE_LARGE', 1000000, 'FLAG', 'Flag first transfer to a payee above 10000 INR');

CREATE TABLE risk_flags (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  transfer_id BIGINT NOT NULL REFERENCES transfers(id),
  rule_code VARCHAR(40) NOT NULL,
  action VARCHAR(10) NOT NULL CHECK (action IN ('HOLD','FLAG')),
  explanation TEXT NOT NULL,
  llm_label VARCHAR(20),
  llm_explanation TEXT,
  reviewed_by VARCHAR(120),
  reviewed_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_risk_flags_review ON risk_flags(reviewed_at, created_at);

CREATE TABLE reconciliation_runs (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  finished_at TIMESTAMPTZ,
  status VARCHAR(20) NOT NULL DEFAULT 'RUNNING',
  checked_count INTEGER NOT NULL DEFAULT 0,
  issue_count INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE reconciliation_issues (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  run_id BIGINT NOT NULL REFERENCES reconciliation_runs(id),
  check_code VARCHAR(50) NOT NULL,
  entity_type VARCHAR(30) NOT NULL,
  entity_id BIGINT,
  expected_value TEXT,
  actual_value TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE job_locks (
  job_name VARCHAR(100) PRIMARY KEY,
  touched_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO job_locks(job_name) VALUES ('nightly-reconciliation');
