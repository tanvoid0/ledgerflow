# LedgerFlow

A double-entry payment ledger with authorisation holds, being built across
services that talk by events. No distributed transaction anywhere.

Java 25 · Spring Boot 4.1 · Maven multi-module · PostgreSQL 17 · k6 · Docker

## What exists today

| service | port | owns |
|---|---|---|
| account-service | 8080 | accounts, wallets, the book of record (journal entries and postings). The only service that moves money. |
| ledger-service | 8081 | funds holds. Asks account whether a wallet exists before reserving against it, then publishes `FundsHeld`. |
| notification-service | 8082 | nothing. Listens on `wallet-hold-events` and logs what it would tell the customer. |

Two shared libraries: `ledgerflow-events` (Money, WalletRef - records only) and
`ledgerflow-starter-web` (the request-id filter as a Boot auto-configuration).

The first event is deliberately naive. `docs/events/README.md` lists the eight
things wrong with it; steps 08-11 fix them one at a time.

## Measured, not claimed

Open model (k6 constant-arrival-rate), single node, Postgres 17 in Docker.
Every raw run is in `docs/perf/`; `perf/compare.sh <before> <after>` reproduces
any row. Write-ups in `docs/measurements/`.

| path / change | load | p50 | p99 | failed |
|---|---|---:|---:|---:|
| Transfer, naive SUM balance | 100/s | 5 | 8 | 0% |
| Transfer, materialised balance + atomic debit | 100/s | 5 | 8 | 0% |
| Hold, both services healthy | 50/s | 7 | 15 | 0% |
| Hold, account frozen, no timeout | 50/s | 30003 | 30008 | 100% |
| Hold, account frozen, 800ms timeout + cached fallback | 50/s | 5 | 809 | 0% |
| Hold, account frozen, + 2 retries | 50/s | 407 | 2012 | 0% |

The overdraft race: 50 concurrent 80.00 debits against a wallet holding 100.00.
Naive code created ten and left the wallet at -700.00; one conditional UPDATE
and a CHECK constraint bring it to exactly one. `perf/race.sh` reproduces it,
`TransferConcurrencyIT` fails if the fix is ever removed.

## The properties, and where they are enforced

| property | enforced by | proved by |
|---|---|---|
| Every entry sums to zero | `JournalEntry` compact constructor | `JournalEntryTest` |
| No transfer applies twice | Idempotency-Key lookup + UNIQUE index | `TransferControllerTest`, live retry |
| No wallet overdrawn under concurrency | conditional UPDATE + CHECK constraint | `TransferConcurrencyIT` (`-DexcludedGroups=none`) |
| A slow dependency cannot take a service down | timeouts, retry in its own bean, fallback | `docs/measurements/step-06-cascade.md` |

## Run it

```bash
docker compose -f infra/compose/docker-compose.yml up -d      # Postgres on host port 5433, Redpanda on 9092
docker exec lf-redpanda rpk topic create wallet-hold-events -p 3
./mvnw -T 1C clean install                                    # builds everything, runs the tests
./mvnw -pl services/account-service spring-boot:run           # terminal 1
./mvnw -pl services/ledger-service spring-boot:run            # terminal 2
./mvnw -pl services/notification-service spring-boot:run      # terminal 3: watch it for "would email the customer"

curl -s localhost:8080/api/v1/accounts | jq
curl -s -X POST localhost:8081/api/v1/holds -H 'content-type: application/json' \
  -d '{"accountId":"11111111-1111-1111-1111-111111111111","wallets":["A-12"],"amountMinor":4500,"currency":"GBP"}' | jq
```

Load and race: `RATE=100 DURATION=60s perf/run.sh transfer baseline`, `perf/race.sh`.
Freeze a service to watch the cascade: `scripts/freeze.sh 8080`, `scripts/freeze.sh 8080 --thaw`.
