CREATE TABLE accounts (
    id   UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL
);

CREATE TABLE wallets (
    id       UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts(id),
    label    VARCHAR(16) NOT NULL,
    UNIQUE (account_id, label)
);
