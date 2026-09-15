# Ninety seconds

Video: coming

Four beats, in order. Nothing here that `scripts/` and Grafana don't already show live -
this page just says where to look.

## 1. Up

```
scripts/demo.sh
```

One command: Postgres, Redpanda, Redis and Grafana in Docker, all eight services started and
waited on until each answers its own health check. Notice: nothing here is a mock - the same
compose file and the same `mvnw spring-boot:run` targets as `README.md`'s "Run it".

## 2. One authorisation, across seven services

`http://localhost:3000` -> Explore -> Tempo -> search by service `payment-service` -> open the
newest trace. Notice: one trace id covers `http post /api/v1/payments`, every `outbox X` /
`publish X` pair (the poller's own thread picking the row up), and every
`ledgerflow.<topic>.v1 process` span on the service that received it - ledger reserves,
issuer authorises, settlement captures, all inside the one root span, none of it stitched
together by hand.

## 3. Capture, and the ledger summing to zero

`curl -s localhost:8085/api/v1/payments/$PAYMENT | jq` moves to `Captured`. Then:

```
docker exec ledgerflow-postgres psql -U ledgerflow -d account -c "SELECT SUM(amount_minor) FROM postings"
```

Notice: every posting is a signed debit or credit, and the whole ledger's postings sum to
exactly `0` after the capture - not close to zero, not rounded to zero, zero - because
double-entry makes it structurally impossible to post one side without the other.

## 4. A burst, a flagged case

```
scripts/scenario-fraud-flagged.sh
```

Thirty payments from one account in a few seconds, then one to a beneficiary already on the
block list. Then `curl -s localhost:8084/api/v1/cases | jq '.[0]'`. Notice: the case note names
the velocity and the z-score that triggered it, in plain English, from a model that never saw
the ledger - and every one of those payments still settled `Captured`, because scoring a
payment and moving its money are two different services that cannot reach each other.
