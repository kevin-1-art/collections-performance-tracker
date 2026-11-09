ALTER TABLE collection_entries RENAME COLUMN amount TO legacy_amount;
ALTER TABLE collection_entries ALTER COLUMN legacy_amount DROP NOT NULL;
ALTER TABLE collection_entries ALTER COLUMN client_name DROP NOT NULL;
ALTER TABLE collection_entries ALTER COLUMN collected_at DROP NOT NULL;
ALTER TABLE collection_entries ADD COLUMN collection_date DATE;
UPDATE collection_entries
SET collection_date=(collected_at AT TIME ZONE 'Africa/Kampala')::DATE;
ALTER TABLE collection_entries ADD COLUMN amount_ugx BIGINT;
UPDATE collection_entries SET amount_ugx=ROUND(legacy_amount)::BIGINT;
ALTER TABLE collection_entries ALTER COLUMN amount_ugx SET NOT NULL;
ALTER TABLE collection_entries
  ADD CONSTRAINT ck_entries_amount_ugx_positive CHECK (amount_ugx > 0);
ALTER TABLE collection_entries ALTER COLUMN collection_date SET NOT NULL;
DROP INDEX IF EXISTS idx_entries_collected_at;
CREATE INDEX idx_entries_collection_date ON collection_entries(collection_date);
