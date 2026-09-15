-- With updated_at this is the payment's end-to-end time, measured on one clock. perf/settled.sh reads it.
ALTER TABLE sagas ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now();
