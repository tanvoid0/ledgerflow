# The overdraft race, reproduced

`perf/race.sh` resets wallet A-20 to exactly 100.00, then fires 50 concurrent
80.00 transfers at it for five seconds. The correct answer is one 201 and the
rest 422.

## Naive use case (read balance, compare, append)

```
transfers created against a 100.00 wallet with 80.00 transfers: 10 (correct answer: 1)
A-20 balance after the race (should never be below 0):
-70000
```

Postgres runs at READ COMMITTED. Ten transactions all ran
`SELECT SUM(amount_minor)` before any of them wrote, all saw 10000, all
decided 8000 fits, all inserted. Two INSERTs on an append-only table never
block each other, so all ten committed with a clean log. `@Transactional`
did exactly what it promises - each entry landed whole - and nothing more.
Atomicity is not isolation.

Every unit test, slice test and curl passed before this ran.

## Materialised balance, atomic conditional debit, CHECK constraint

`UPDATE wallets SET balance_minor = balance_minor - :amt WHERE id = :id AND balance_minor >= :amt`
does the check and the write in one statement. Zero rows means refused. The row
lock serialises the racers; `CHECK (balance_minor >= 0)` on the column is the
floor under any future bug in Java.

```
transfers created against a 100.00 wallet with 80.00 transfers: 1 (correct answer: 1)
A-20 balance after the race (should never be below 0):
2000
```

Reconciliation after the race and after a 60s load run: every wallet's column
equals the sum of its postings, none below zero.

| run | p50 ms | p95 ms | p99 ms | req/s | failed |
|---|---:|---:|---:|---:|---:|
| transfer-baseline (SUM over postings) | 5 | 7 | 8 | 83 | 0% |
| transfer-materialised (column + atomic debit) | 5 | 7 | 8 | 83 | 0% |

Latency did not move: with ~15k postings a SUM over an indexed wallet is as
cheap as a column read. The materialised balance is O(1) where the SUM is
O(postings), so the gap opens as the book grows - but the reason to ship it
today is the race, not the milliseconds.

Three correct fixes, for the record: the atomic conditional UPDATE (above,
right when the rule is a predicate on one row); `SELECT ... FOR UPDATE` (when
the decision needs several reads while holding the row - lock in id order or
you will deadlock); a `@Version` column (optimistic, right when conflicts are
rare - wrong for a hot wallet, where fifty racers means forty-nine retries).
