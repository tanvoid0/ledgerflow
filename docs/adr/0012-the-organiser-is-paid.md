# 0012 — The organiser is paid, the other half of the money story

## Context

Every money step so far moves the buyer's money *in*: a hold reserves the buyer's wallet, a capture
transfers it to TREASURY (the settlement wallet), a refund sends it back. ADR 0009 named what none of
them did — "the organiser payout that is the other half of the money story" — and left it deferred.
Captured ticket money sat in TREASURY and the organiser was never credited.

Two gaps kept the money there:

- The nightly settlement batch (steps 22–29) nets captured money per merchant into `settlement_batch`
  and emits `MerchantSettled`, but **nothing consumed it** — the backlog's "no production feeder". The
  number was announced; no money moved.
- The batch's own input, `settlement_item`, had **no production writer** either. Every step that built
  the batch seeded it from tests; a real captured payment never produced one.

And an organiser was only a free-text name (`events.organiser`, max 64, passed to payment as
`beneficiary`). It had no account and no wallet to be paid into.

## Decision

Complete the path with three small links, each reusing a primitive that already exists. No new money
mechanism, no saga change — `beneficiary` never reaches the saga state (it rides only
`PaymentRequested`), so the feed is driven from where the organiser is actually known.

- **Ticketing feeds the batch.** `confirm()` already moves an order CONFIRMED on `PaymentCaptured`,
  in a transaction with its outbox (grown in step 35 for `RefundOrder`). It now appends one
  `settlement.SettleItem(paymentId, merchantId = event.organiser, amountMinor, currency,
  businessDate = the event's day in UTC)` on the normal confirm. Settlement grows a `SettleItemListener` that inserts
  the `settlement_item` row; the shared inbox dedupes on `eventId`, so a redelivered capture inserts
  nothing twice. This is the first production writer of `settlement_item`.

- **An organiser is a payout account, derived from its name.** `accountId =
  UUID.nameUUIDFromBytes("organiser:" + name)`, with a MAIN wallet and no opening balance (organisers
  earn, they are not granted a balance). `V10__demo_organisers.sql` seeds the six demo-event
  organisers. No name→account table: the listener derives the same id the seed wrote.

- **Settlement pays out on its own `MerchantSettled`.** A `MerchantPayoutListener` resolves
  `merchant_id` → organiser accountId → MAIN wallet via the account client it already uses for
  captures, then transfers `net_minor` out of the settlement wallet (TREASURY) into that wallet. The
  Idempotency-Key is `payout-<merchantId>-<businessDate>`: a batch rerun, or a redelivered event,
  re-asks account for the same transfer and gets the same entry, so paying twice is impossible. A
  non-positive net is skipped; an unseeded organiser (no payout account) is logged and skipped rather
  than poisoning the listener.

The buyer paid `gross` into TREASURY; the organiser gets `net` out of it; the platform's fee
(`gross − net`, the batch's 2.9% + 30) stays in TREASURY. Double-entry holds at every step, and
TREASURY is allowed negative by construction (`JdbcLedgerRepository.debitIfSufficient`), so the
payout never fails for funds.

## Consequences

- **The settlement batch now has a real input.** It was a batch-mechanics teaching vehicle fed by
  seeds; it is now fed by every confirmed order. Its scaling work (partitioning, remote chunking)
  finally runs on production data.
- **Payout is off the hot path.** One transfer per merchant per business date, driven by the nightly
  job, not by checkout. No new bench: the money path under load is unchanged (capture, hold, transfer
  are what the benches already measure).
- **A refund before the batch takes the item out of the net.** Settlement's revoke marks the
  payment's `settlement_item` REVOKED while it is still NEW, and an item that arrives after its
  revoke (two topics, no order between them) is inserted REVOKED. Without this, a cancelled order
  was refunded to the buyer and paid to the organiser, both out of TREASURY.
- **The organiser is paid the night after the event, not the night after the sale.** The first cut
  dated items "today", so a buyer cancel (allowed up to 24h before the event) or an organiser cancel
  almost always landed after the payout, and TREASURY absorbed the refund. Dated by the event's day,
  the 01:05 cron nets them only once the event is over, so every cancel before then is a revoke on an
  unpaid item. Holding the money until the event happens is also what ticket platforms do.
- **A refund after the payout is clawed back.** Only an organiser cancel after the event day can hit
  it. The revoke finds the payment's SETTLED item and moves its `net_minor` from the organiser's
  wallet back to TREASURY, keyed `clawback-<itemId>`, then marks it CLAWED_BACK. The platform's fee
  is not recovered. If the payout has not landed yet or the wallet is short, it logs and skips; a
  negative net carried to the next business date would cover that.
- **The event's day is its UTC date.** An event starting late in the UTC day is netted at 01:05, an
  hour or two after it starts rather than a day; fine for a demo.
- **Deferred, still.** On-demand organiser onboarding at event-create (vs the seed); a `MerchantPaid`
  outcome event for the ops console; partial / within-day payouts; FX-currency payout nuances beyond
  what the batch already nets.
