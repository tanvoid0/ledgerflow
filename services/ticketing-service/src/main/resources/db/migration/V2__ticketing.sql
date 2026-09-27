-- events: the catalog. status ON_SALE|CANCELLED.
CREATE TABLE events (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    slug        TEXT         NOT NULL UNIQUE,
    title       TEXT         NOT NULL,
    subtitle    TEXT,
    venue       TEXT         NOT NULL,
    city        TEXT,
    category    TEXT,
    starts_at   TIMESTAMPTZ  NOT NULL,
    organiser   TEXT         NOT NULL,
    price_minor BIGINT       NOT NULL,
    currency    CHAR(3)      NOT NULL,
    status      TEXT         NOT NULL DEFAULT 'ON_SALE',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- orders: the hold is the row. One expires_at, no holds table, no Redis.
-- seats is jsonb, not a native array: no other table in this codebase binds a Postgres array through JdbcClient,
-- and jsonb is the established idiom here (sagas.payload, risk_decision.features) - same wire shape, proven binding.
CREATE TABLE orders (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id     UUID         NOT NULL REFERENCES events(id),
    account_id   UUID         NOT NULL,
    username     TEXT,
    seats        JSONB        NOT NULL,
    amount_minor BIGINT       NOT NULL,
    currency     CHAR(3)      NOT NULL,
    status       TEXT         NOT NULL,
    expires_at   TIMESTAMPTZ  NOT NULL,
    request_id   TEXT,
    payment_id   UUID         UNIQUE,
    fail_reason  TEXT,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_orders_account ON orders (account_id, created_at DESC);
CREATE INDEX idx_orders_expiry ON orders (expires_at) WHERE status = 'HELD';

-- seats: status FREE|HELD|SOLD. row_label/seat_number are for the seat map the UI draws; seat is the id ("C12").
CREATE TABLE seats (
    event_id    UUID    NOT NULL REFERENCES events(id),
    seat        TEXT    NOT NULL,
    row_label   TEXT,
    seat_number INT,
    status      TEXT    NOT NULL DEFAULT 'FREE',
    order_id    UUID    REFERENCES orders(id),
    PRIMARY KEY (event_id, seat)
);
