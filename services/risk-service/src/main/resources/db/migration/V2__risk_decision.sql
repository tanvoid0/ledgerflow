-- Append only. A decision is evidence, and evidence does not get updated.
CREATE TABLE risk_decision (
    payment_id   UUID           PRIMARY KEY,
    decision     TEXT           NOT NULL,
    rule_fired   TEXT,
    model_version TEXT          NOT NULL,
    score        NUMERIC(6,5)   NOT NULL,
    features     JSONB          NOT NULL,
    decided_at   TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE TABLE risk_case (
    payment_id   UUID  PRIMARY KEY REFERENCES risk_decision,
    narrative    TEXT  NOT NULL,
    generated_by TEXT  NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
