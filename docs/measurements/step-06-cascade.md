# Cascading failure, measured

50 req/s open model against `POST /api/v1/holds` on ledger-service, 30s each,
account-service frozen with `scripts/freeze.sh 8080` (SIGSTOP / NtSuspendProcess:
the port stays open and answers nothing, which is what an overloaded service
looks like from outside). Raw runs in `docs/perf/holds-*.json`.

| scenario | p50 ms | p99 ms | failed | ledger `/actuator/health` meanwhile |
|---|---:|---:|---:|---|
| both healthy | 7 | 15 | 0% | 200 in 4ms |
| account frozen, no timeout | 30003 | 30008 | 100% | timed out after 5s |
| account frozen, 500/800ms timeouts + cached fallback | 5 | 809 | 0% | 200 in 4ms |
| account frozen, timeouts + 2 retries (200ms) + fallback | 407 | 2012 | 0% | 200 in 4ms |

## What moved, and why

**No timeout.** Every request waited on a socket that would never answer.
The 30s is not the HTTP client: it is HikariCP's `connectionTimeout`.
`PlaceHold` was `@Transactional`, so each request pinned a database connection
*before* calling account; ten stuck requests drained the pool and the next
thousand queued 30s for a connection that never came back. Ledger's own health
endpoint - nothing to do with account - stopped answering. That is the cascade.
Two changes: read timeout 800ms, and the network call moved out of the
transaction (the write is its own short transaction).

**Timeout + fallback.** p99 collapses to the read timeout. The first few
connections sit in the frozen process's accept backlog until the 800ms read
timeout; once the backlog is full, connects are refused in ~5ms. Either way the
gateway serves the last known account view and ledger keeps taking business.

**Retries.** Real now (the first version called the `@Retryable` method on
`this`, which bypasses the proxy - no retry ever ran). Against a *dead*
dependency, retrying costs p50 407ms instead of 5ms and p99 2s instead of
0.8s: two refused connects plus two 200ms pauses per request. Retries are for
blips; for an outage, a circuit breaker that stops paying the timeout is the
next step and is deliberately not here yet.
