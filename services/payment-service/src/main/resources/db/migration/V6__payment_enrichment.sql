-- Phase 3: an async, LLM-derived read model. A consumer tags each settled payment/refund with a
-- {category, reasonSummary}; the assistant groups by it. A wrong tag embarrasses a dashboard, never a
-- payment (ADR 0005). (payment_id, kind) is the key: one payment can gain a 'payment' row and, later, a
-- 'refund' row, and a replayed event hits the key instead of writing twice.
CREATE TABLE payment_enrichment (
    payment_id     UUID         NOT NULL,
    kind           VARCHAR(16)  NOT NULL,   -- 'payment' | 'refund'
    category       VARCHAR(64)  NOT NULL,
    reason_summary VARCHAR(280) NOT NULL,
    amount_minor   BIGINT       NOT NULL,
    currency       VARCHAR(3)   NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (payment_id, kind)
);
CREATE INDEX idx_payment_enrichment_kind_category ON payment_enrichment (kind, category);
