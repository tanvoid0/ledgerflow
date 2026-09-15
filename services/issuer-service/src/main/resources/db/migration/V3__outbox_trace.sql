-- The trace the event was written in (W3C traceparent, as the propagator wrote it). The poller restores it
-- around the send, so the Kafka hop hangs off the request that caused it and not off a timer.
ALTER TABLE outbox ADD COLUMN trace_context JSONB NOT NULL DEFAULT '{}'::jsonb;
