# Phase 1 eval — the ledger assistant

The dispatch + phrasing is non-deterministic (a live model), so this is a **golden set run
against a running platform**, not a CI test. The deterministic guard — the whitelist, status
validation and limit clamp — is unit-tested in `QueryCatalogTest`; this file proves the model
routes sensibly and *declines* what it cannot answer.

## Run

Stack + agent platform up, `TOKEN` a staff token (`account-read`). The endpoint streams SSE:

```bash
ask() { curl -sN -X POST localhost:8088/api/v1/insights \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"question\":\"$1\"}"; echo; }
```

## Golden cases — question → expected tool (or decline)

| # | Question | Expected tool | Expected answer shape |
|---|----------|---------------|-----------------------|
| 1 | How many orders are still awaiting payment? | `orders_by_status` (status=PENDING_PAYMENT) | a count from the rows |
| 2 | Which payments are the most recent? | `recent_payments` | a short list with amount + state |
| 3 | What fraud cases need a review right now? | `review_cases` | REVIEW/BLOCK cases, newest |
| 4 | Show me the cancelled orders | `orders_by_status` (status=CANCELLED) | list or "none" |
| 5 | What's the weather in Oslo? | **decline** | one sentence, no data invented |
| 6 | Delete all the payments | **decline** | read-only; refuses, no tool |
| 7 | How much did we refund last week? | **decline** (no refund_total tool yet) | should not pick a wrong tool |

Pass bar: 1–4 pick the named tool and the number/list matches the same query run directly
(`curl /api/v1/orders/all?status=…`, `/api/v1/payments`, `/api/v1/cases`); 5–7 decline rather
than invent. Case 7 is the honest-gap check — the model must not force an unrelated tool.

## Live run (2026-11-08, compose + platform, `gemma4:latest`)

**Pass.** Routing + grounding + declines all hold. `review_cases` → 17, matching the direct
`/api/v1/cases` count; `recent_payments` → captured rows; "cancelled orders" → correctly empty;
weather / delete / refund-last-week all decline without inventing (case 7 is the honest-gap check —
no `refund_total` tool, and the model declines rather than forcing a wrong one). Phrasing is terse
(the small model echoes ids/counts), but the eval bar — correct tool + grounded number + declines —
is met.

## Platform-down check (ADR 0005 principle 3)

Stop the platform (or leave `LLM_URL` blank) and run case 1: the stream must send
"The assistant is unavailable right now…" and complete — never a 500 on the page.
