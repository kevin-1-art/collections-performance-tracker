PRAGMA foreign_keys=ON;
CREATE TABLE accounts (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  username VARCHAR(80) NOT NULL UNIQUE,
  display_name VARCHAR(160) NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  role VARCHAR(20) NOT NULL CHECK (role IN ('ADMIN','OFFICER')),
  enabled BOOLEAN NOT NULL DEFAULT 1,
  must_change_password BOOLEAN NOT NULL DEFAULT 0,
  failed_attempts INTEGER NOT NULL DEFAULT 0,
  locked_until TIMESTAMP,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE collection_entries (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  client_name VARCHAR(160) NOT NULL,
  tracking_number VARCHAR(80) NOT NULL UNIQUE,
  prn VARCHAR(80) NOT NULL UNIQUE,
  amount DECIMAL(14,2) NOT NULL CHECK (amount > 0),
  collected_at TIMESTAMP NOT NULL,
  receipt_number VARCHAR(80),
  notes VARCHAR(1000),
  created_by BIGINT NOT NULL REFERENCES accounts(id),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_entries_collected_at ON collection_entries(collected_at);
CREATE INDEX idx_entries_created_by ON collection_entries(created_by);
CREATE UNIQUE INDEX uq_entries_tracking_number_ci ON collection_entries (LOWER(tracking_number));
CREATE UNIQUE INDEX uq_entries_prn_ci ON collection_entries (LOWER(prn));
CREATE UNIQUE INDEX uq_accounts_username_ci ON accounts (LOWER(username));
CREATE TABLE audit_events (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  actor_id BIGINT REFERENCES accounts(id),
  action VARCHAR(40) NOT NULL,
  entity_type VARCHAR(40) NOT NULL,
  entity_id VARCHAR(100),
  details VARCHAR(1000),
  occurred_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
