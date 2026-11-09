CREATE TABLE collection_entries_new (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  client_name VARCHAR(160),
  tracking_number VARCHAR(80) NOT NULL,
  prn VARCHAR(80) NOT NULL,
  legacy_amount DECIMAL(14,2),
  collected_at TIMESTAMP,
  receipt_number VARCHAR(80),
  notes VARCHAR(1000),
  collection_date DATE NOT NULL,
  amount_ugx INTEGER NOT NULL CHECK (amount_ugx > 0),
  created_by BIGINT NOT NULL REFERENCES accounts(id),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uq_entries_tracking_number UNIQUE (tracking_number),
  CONSTRAINT uq_entries_prn UNIQUE (prn)
);
INSERT INTO collection_entries_new(id,client_name,tracking_number,prn,legacy_amount,collected_at,receipt_number,notes,
                                   collection_date,amount_ugx,created_by,created_at)
SELECT id,client_name,tracking_number,prn,amount,collected_at,receipt_number,notes,
       date(collected_at,'+3 hours'),CAST(ROUND(amount) AS INTEGER),created_by,created_at
FROM collection_entries;
DROP TABLE collection_entries;
ALTER TABLE collection_entries_new RENAME TO collection_entries;
CREATE INDEX idx_entries_collection_date ON collection_entries(collection_date);
CREATE INDEX idx_entries_created_by ON collection_entries(created_by);
CREATE UNIQUE INDEX uq_entries_tracking_number_ci ON collection_entries(LOWER(tracking_number));
CREATE UNIQUE INDEX uq_entries_prn_ci ON collection_entries(LOWER(prn));
