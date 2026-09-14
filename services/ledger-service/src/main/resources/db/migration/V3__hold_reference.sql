-- What the hold is for: the payment that asked for it. Release and capture address holds by it.
-- Null for a hold placed over HTTP with no caller reference.
ALTER TABLE funds_holds ADD COLUMN reference UUID;
CREATE INDEX idx_funds_holds_reference ON funds_holds (reference) WHERE reference IS NOT NULL;
