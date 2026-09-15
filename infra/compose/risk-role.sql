-- risk-service connects as this role and nothing else; it cannot open the ledger's database
-- (or any other service's), so no code path in it can write to money it did not decide about.
CREATE ROLE risk LOGIN PASSWORD 'risk';
ALTER DATABASE risk OWNER TO risk;
REVOKE CONNECT ON DATABASE account, ledger, notification, payment, issuer, settlement, balance FROM PUBLIC;
