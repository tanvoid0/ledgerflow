-- Phase 2: the narrator now returns structured output. Confidence is how unusual the model thinks the
-- payment looks (0..1), nullable because a template fallback has no model opinion to record.
ALTER TABLE risk_case ADD COLUMN confidence NUMERIC(4,3);
