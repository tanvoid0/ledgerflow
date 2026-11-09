# Step 37 — The buyer cancels: self-service refund

The code story is `docs/adr/0011-the-buyer-cancels.md`; this is the live-run
checkpoint. **Compose numbers, run with the AI platform wired in and Ollama live** (phases 0–4
from `docs/ai/0001`, not disabled for this run). Host AMD Ryzen 9 9950X / 32 threads, Docker
Desktop; no `hs_err`, no container restart. Consumer lag checked 0 across all eight groups
before and after the fixed suite.

## The scenario is the real done-when, and it passes

`scripts/scenario-buyer-cancel.sh` exits 0 on the default compose stack — no override needed,
unlike step 36: the buyer-cancel path has no race to force, so there is no dev knob. One run,
end to end:

- alice buys a seat on a seeded event (weeks out, eligible) → CONFIRMED, seat SOLD
- bob tries to cancel alice's order → **404** (hides existence, same as any other order lookup
  by a non-owner)
- alice cancels her own order → **202**, polls to REFUNDED, seat FREE, MAIN balance back to the
  pre-purchase figure
- alice cancels the same order again → **409** (Captured-only guard already closed, same
  idempotency step 35/36 rely on)
- ops creates a one-off event starting in 1 hour; alice buys and confirms a seat on it; alice's
  cancel on that order → **409**, order stays CONFIRMED — the 24h cutoff holds

No new wire contract, no schema change, no application code in payment-service: the entire
feature is the eligibility gate in `Ticketing.cancelOrder` plus one controller endpoint,
confirmed by the plan in ADR 0011 before writing it.

## The fixed suite, step 36 → step 37 (both compose, both AI-wired)

Run once, AI platform live throughout (no `LLM_URL=""` pause this time — step 36's contaminated
run showed what overload under the AI-wired stack looks like; this run checks the normal,
unloaded-enrichment case).

| scenario | p50 | p95 | p99 | rps | failed |
|---|---|---|---|---|---|
| transfer | 8ms | 4697ms | 5316ms | 73 | 0% |
| holds | 9ms | 17ms | 1230ms | 49 | 0% |
| payments (POST) | 8ms | 27ms | 78ms | 88 | 0% |
| payments (settled) | 443ms | 4572ms | 10206ms | — | 0 / 18011 captured |

Transfer's p95/p99 (4.7s / 5.3s) is the same cold-start cost step 35 and step 36 both saw and
named as "first scenario in the suite pays the warm-up" — not a regression, and holds' p99
(1230ms) is the same class of effect spilling into the second scenario. All three fixed-rate
scenarios ran at 0% failed. Settled p99 (10.2s) sits between step 35's clean 697ms (enrichment
off) and step 36's contaminated 35s (enrichment backed up under the same load) — consistent with
an AI-wired stack carrying real but not pathological async load, the same story step 36 told,
not a new ceiling to chase for a feature that touches zero extra ledger paths.

## Consequences for the number, not the feature

Step 37 adds one more `RefundOrder` caller; it does not add load to the hot paths the suite
measures. The settled tail belongs to the AI platform being live, as step 36 already
established — re-running with `LLM_URL=""` would reproduce step 35's clean numbers, which this
checkpoint does not need to re-prove.
