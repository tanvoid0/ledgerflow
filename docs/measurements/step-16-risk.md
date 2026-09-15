# Step 16 — a model that never touches the ledger

## Beside the path, not on it

risk-service consumes `payment.PaymentRequested`
(`libs/ledgerflow-events/src/main/java/io/ledgerflow/events/payment/PaymentRequested.java`), appended to
the outbox by `Payments.start` inside the same transaction as the saga's first command
(`services/payment-service/src/main/java/io/ledgerflow/payment/application/Payments.java`). It is not a
saga step: nothing replies to it, and a payment risk-service never saw still captures exactly as it did
in step 15. Its own consumer group (`risk-service`) gets its own copy of the topic, so nothing it does
can slow the seven services that were already there. It reads nothing else - no HTTP call to account or
ledger, no shared database - and its Postgres role (`risk`) cannot even CONNECT to theirs
(`infra/compose/risk-role.sql`; more under "Two controls"). Features live in Redis db 1; balance-service
owns db 0, and the two read models don't share a key.

## The model

Three features, rebuilt from the stream one event at a time by `FeatureWindow.observe`
(`services/risk-service/src/main/java/io/ledgerflow/risk/application/FeatureWindow.java`,
`services/risk-service/src/main/resources/features.lua`): how many payments this account made in the
last 60 seconds, how far this amount sits from the account's own running mean (a z-score, computed from
the mean and variance from *before* this payment, so a payment never inflates its own baseline), and
whether this beneficiary is new to the account. Same three numbers, same order, feed both training and
serving, which is what keeps the two honest.

There is no labelled fraud history here - this is a ledger with no chargebacks - so
`scripts/train-risk-model.py` builds a synthetic set: `countLastMinute` mostly 1-5 with a 5% heavy tail,
`amountZScore` ~N(0,1) with a 5% fat-tailed outlier, 15% of rows carrying a new beneficiary. The label is
a hand-written rule (`count >= 8` OR `z >= 3` OR `(new beneficiary AND z >= 1.5)`) with 5% flipped at
random. Logistic regression on the three raw features, 80/20 split, seed 42, 20,000 rows:

- held-out AUC: **0.7931**
- coefficients: `countLastMinute` 0.3361, `amountZScore` 0.5226, `newBeneficiary` 0.7091, intercept
  -3.8812
- sanity: `score([0,0,0]) = 0.0202` (a quiet account, low), `score([12,4,1]) = 0.9503` (a loud one against
  a new beneficiary, high)

0.79 is fine, not excellent, and the label says why. The rule's third clause is an AND between
`newBeneficiary` and `z >= 1.5` - an interaction - and a linear model with no interaction term can't draw
that boundary. It can only add a weighted `newBeneficiary` to a weighted `z`, which either flags every new
beneficiary regardless of amount or misses a new beneficiary at a merely elevated one; the 0.79 is the
cost of that gap, not a bug in the training run. A model that fit the interaction exactly would also be a
model nobody could reproduce from a `risk_decision.features` column by eye, and reproducibility is the
point of staying this simple. Exported to ONNX (skl2onnx 1.20.0, opset 18), with `version=lr-v1` written
into the model's own metadata by the training script and read back once, at startup, by `Scorer`'s
constructor (`services/risk-service/src/main/java/io/ledgerflow/risk/application/Scorer.java`) - the
version that travels with every decision comes from the file, not a constant somebody could forget to
bump.

## Rules decide, the model ranks

`Rules.decide` (`services/risk-service/src/main/java/io/ledgerflow/risk/application/Rules.java`) checks
the hard rules first - an amount over the limit, a beneficiary already known bad - and only they can
produce `Decision.Block`
(`services/risk-service/src/main/java/io/ledgerflow/risk/domain/model/Decision.java`). The score decides
nothing by itself: at or above `risk.review-threshold` (0.8) it can only move a payment to `Review`, never
to `Block`, and below it the payment is `Allow` whether or not a rule exists to say so. Every decision,
whichever it is, is one row in `risk_decision`
(`services/risk-service/src/main/resources/db/migration/V2__risk_decision.sql`) - the decision, the rule
that fired if one did, the model version, the score, and the features that produced it - written by
`Decisions.record`
(`services/risk-service/src/main/java/io/ledgerflow/risk/application/Decisions.java`) with no UPDATE
statement anywhere near the table. A REVIEW from six months ago, scored by a model since retrained, is
still reconstructable from that row alone.

One run of `scripts/replay-fraud.sh` (30 payments from one account, rotating wallets, plus one to the
blocked beneficiary `mule-1`) landed 15 ALLOW, 15 REVIEW, 1 BLOCK - all 31 rows carrying a model version
and a score, the BLOCK included: `Scorer.score` runs before `Rules.decide` gets a chance to short-circuit
on the rule, so the audit row for `mule-1` still reads `score 0.99929`, `modelVersion lr-v1`, even though
the rule, not the score, is what actually fired. Its `narrative`/`generated_by` are null, and always will
be - `Narrator.writeCases` only queries `decision = 'REVIEW'`, so a BLOCK never gets a case note; a rule
has already decided, and there is nothing left for an analyst to review. All 31 saga states in
payment-service settled `Captured` - the one BLOCK moved no money and stopped nothing.

What this step does not do: enforce any of it. `PaymentRequestedListener`
(`services/risk-service/src/main/java/io/ledgerflow/risk/adapter/in/messaging/PaymentRequestedListener.java`)
scores and records every payment, but nothing feeds a REVIEW or a BLOCK back into `PaymentSaga` - the
saga in payment-service runs exactly as it did in step 15, and a payment risk-service would block still
captures. Making the decision count means a fourth reply into the saga (`RiskDecided`, alongside
`FundsHeld`, `PaymentAuthorized`, `CapturesIssued`) and a new transition in `PaymentSaga.on` that can hold
or reject on it. That's a later step, not this one - saying so here is the honest version of this
section.

## The narrative

Every REVIEW gets a case note, written by a sweeper (`Narrator.writeCases`, `@Scheduled(fixedDelay =
1000)`, `services/risk-service/src/main/java/io/ledgerflow/risk/application/Narrator.java`) that pulls
up to `risk.narrator.batch` (5) unwritten cases a tick, `FOR UPDATE SKIP LOCKED` so a second instance
wouldn't write one twice. The prompt hands over four numbers off the decision's own `features` column -
amount, count in the last minute, z-score, new beneficiary or not - and asks for two sentences: what's
unusual, what to check, no recommendation. The call is a plain `RestClient` built once from
`NarratorProperties`
(`services/risk-service/src/main/java/io/ledgerflow/risk/application/NarratorProperties.java`: `url`,
`model`, `apiKey`, `batch`, timeout) against any OpenAI-compatible chat endpoint - today Ollama on the
same machine (`gemma4`, no key), so the features never leave the box; a hosted provider (ollama.com,
aimlapi, Gemini all speak the same wire shape) is a `url`/`model`/`api-key` change, not code. A blank or
failed response falls back to the template, and `generated_by` says which model wrote the note, or
`template` when nothing did.

Two operational facts, both found by running it, not by reading the code first. `gemma4` is a "thinking"
model: it spends ~400 tokens reasoning before it answers, so `risk.narrator.max-tokens` ships at 800, not
a round number - at the old 300 the whole budget went on reasoning, `content` came back empty, and the
fallback (correctly) wrote the template every time. And the sweeper's own query used to take the oldest
pending REVIEW first, strict FIFO: after a load run queued 18,587 REVIEWs, a single fresh case from
`replay-fraud.sh` sat behind that entire backlog for **8 minutes**, not the ~30s five-a-tick suggests,
because each `narrate()` call is a real, sequential HTTP round-trip (~3.7s) and most of the backlog aged
out past `risk.narrator.max-age` (10m) before its turn came anyway. The fix is the query, not the timeout:
`ORDER BY d.decided_at DESC` (`Narrator.java`) puts the case an analyst is looking at now ahead of a load
test's backlog; `max-age` still retires whatever's left rather than narrating it stale.

The first REVIEW case, narrated live after that fix:

```json
{
  "paymentId": "9c667fff-535d-4ea1-bf2f-1600df792242",
  "decision": "REVIEW", "ruleFired": null, "score": 1.0, "modelVersion": "lr-v1",
  "features": "{\"amountMinor\":5000,\"currency\":\"GBP\",\"beneficiary\":\"burst-1789475583\",\"countLastMinute\":30,\"amountZScore\":30.497540884471,\"newBeneficiary\":false}",
  "narrative": "The unusually high payment velocity (30 transactions in the last minute) and the extreme z-score of 30.50 are highly unusual indicators of potential account compromise or rapid testing. Review the source of the recent activity, verify the customer's current location, and confirm the authorization status of the beneficiary relationship.",
  "generatedBy": "gemma4"
}
```

Two sentences, no decision recommended, `generated_by: gemma4` - a real model, not the template. Every
one of 150 live calls made so far succeeded (`generated_by, count(*) group by 1`: `gemma4 150, template
6915` from before this restart) with no WARN in the service log, at ~3.7s a call - the real cost of the
batch, and the reason the queue order mattered more than the model did. Nothing downstream reads the
note back either way - a `CasesController` GET is the only consumer
(`services/risk-service/src/main/java/io/ledgerflow/risk/adapter/in/web/CasesController.java`) - so a
wrong or missing sentence can embarrass an analyst, never a payment.

## Two controls

Two independent things stop the model from ever moving money - a test that gets deleted should not be
the only thing standing there.

`ArchitectureTest`
(`services/risk-service/src/test/java/io/ledgerflow/risk/ArchitectureTest.java`) forbids any class in
`io.ledgerflow.risk..` from depending on `io.ledgerflow.ledger..`, `io.ledgerflow.account..`,
`OutboxAppender`, or `KafkaTemplate`. It catches the day somebody imports the ledger's domain model to
"just read one thing," and the day somebody wires risk-service to publish an event of its own -
`ledgerflow.outbox.enabled=false` already removes the `OutboxAppender`/`OutboxPublisher` beans from the
context, so the second half of the rule is enforcing an absence that's also true at runtime. What it does
not catch: a plain HTTP call. Nothing in the rule looks at `RestTemplate` or `WebClient`, so a class that
called account-service's `/transfers` endpoint directly - the same route settlement-service already uses
to move real money - would compile and pass the test clean.

`RiskRoleIT` (`services/risk-service/src/test/java/io/ledgerflow/risk/RiskRoleIT.java`) runs
`infra/compose/init-databases.sh` for real against a throwaway Postgres and proves the other half: the
`risk` role opens its own database and gets `permission denied for database` on every other one, ledger
included (`infra/compose/risk-role.sql` is the same statements, for an existing volume that predates this
step). That catches exactly the gap ArchUnit leaves - an HTTP call never touches risk's database role at
all - but it also closes a door ArchUnit doesn't: no code running as `risk`, however it got there, can
read the ledger's tables directly either. Between the two, every path actually open in this codebase -
a Java dependency, an outbox row, a shared table - is closed. An HTTP client added specifically to reach
across is the one door neither control locks; it's also the one that would show up in a five-minute code
review, which is as far as this step goes.

## Under load

Same profile as step15 (100/s, 180s, `perf/run.sh payments <label>`), risk-service up as the eighth local
JVM. Three back-to-back readings of the same scenario gave three different answers before a fourth,
controlled one settled it:

| run | settled p50 | p95 | p99 | captured | failed |
|---|---:|---:|---:|---:|---:|
| step15 | 367 | 725 | 1088 | 18007 | 0 |
| step16 | 391 | 773 | 996 | 18008 | 0 |
| risk-on (warm) | 412 | 1414 | 2261 | 18008 | 0 |
| ab-off-1 | 396 | 813 | 1241 | 18007 | 0 |
| ab-on-1 | 414 | 847 | 1305 | 18008 | 0 |
| ab-off-2 | 447 | 1340 | 1847 | 18008 | 0 |
| ab-on-2 | 410 | 840 | 1009 | 18007 | 0 |

Cold (5707ms, the first payments load these eight JVMs ever saw) -> `step16`'s checkpoint (996ms, warm) ->
a second warm rerun (2261ms): the same scenario, three different numbers - not an answer, a reason to run
a controlled experiment instead of another single one. Stop risk-service, run, start it, run, twice each,
alternating, every consumer group confirmed `Stable`/lag 0 between phases so no run started behind:

| group | p99 values | mean | spread (max-min) |
|---|---|---:|---:|
| off (risk-service down) | 1241, 1847 | 1544 | 606 |
| on (risk-service up) | 1305, 1009 | 1157 | 296 |

Scoring held at 0.99ms p99 on both `on` runs, same as every other reading in this step. The two **off**
runs - risk-service not even in the picture - differ from each other by 606ms, more than the entire
on/off mean gap (1544 - 1157 = 387ms, and *on* is the faster mean). Risk-service does not move settled
p99 beyond the spread that's already there with it turned off; that spread is the same thing step 15
named as this path's ceiling, fifteen fsyncs a payment on one Docker Desktop virtual disk. Pinning down
why one run's fsyncs run slower than another's is not this step's question - the A/B answers this step's,
and the answer is no.

Decisions: essentially every load payment scores REVIEW once the burst window fills, ALLOW only for the
handful that land before `countLastMinute` climbs past the threshold - a burst by construction, not the
model failing. A real account doing this would be frozen, not scored; the number here is a cost, not a
verdict.

## Kept

- `ledgerflow.outbox.enabled=false`: risk-service publishes nothing, and the property removes the beans
  instead of leaving them unused.
- Redis db 1 for features; db 0 stays balance-service's.
- `risk.review-threshold=0.8`: the score alone never blocks, only reviews, above this line.
- `risk.rules.amount-limit-minor=1000000`, `risk.rules.blocked-beneficiaries=[mule-1]`: two numbers a
  regulator could ask for and get an answer from config, not code.
- `risk.narrator.batch=5`: the LLM call is the slow part of the sweep, not the query.
- `risk.narrator.url` defaulting to a local Ollama endpoint (`gemma4`): the case note's inputs never
  leave the machine unless the config is pointed elsewhere.
- `risk.narrator.max-tokens=800`: room for this model's ~400 tokens of reasoning plus a real answer.
- `risk.narrator.max-age=10m` and `ORDER BY decided_at DESC`: a fresh case beats a load test's backlog to
  the front of the queue, and the rest ages out instead of narrating stale.
- The append-only `risk_decision` table: no UPDATE statement anywhere near it.
- Evidence: `docs/perf/payments-risk-on.json`, `docs/perf/payments-ab-off-1.json`,
  `payments-ab-on-1.json`, `payments-ab-off-2.json`, `payments-ab-on-2.json`.
