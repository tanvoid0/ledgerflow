-- There is no mail server. This row is the send, and it is what a replay would double.
CREATE TABLE sent_notifications (
    id      UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    hold_id UUID        NOT NULL,
    sent_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
