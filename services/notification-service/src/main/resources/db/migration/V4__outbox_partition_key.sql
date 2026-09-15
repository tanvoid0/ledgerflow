ALTER TABLE outbox ADD COLUMN partition_key TEXT;   -- null = key by aggregate_id, as before
