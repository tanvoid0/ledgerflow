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
