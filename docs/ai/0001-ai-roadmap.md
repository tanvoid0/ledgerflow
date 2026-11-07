# AI roadmap: LLM capabilities on the ledger

Status: in progress (2026-11-07) · **Phase 0, 1 backend, 2, 3, 4 backend shipped** (web UI deferred throughout)

This sequences four AI capabilities onto LedgerFlow, ordered by portfolio
impact and ascending risk, each building on the one before. The target LLM
is the local **agent platform** — an OpenAI-compatible API (`/v1/chat/completions`,
`/v1/embeddings`) supporting tool/function calling, JSON/structured output and
streaming.

## Where we already are

There is already one LLM integration in the repo, and it sets the pattern:

- `risk-service`'s `Narrator` (`io.ledgerflow.risk.application.Narrator`) calls
  an OpenAI-compatible `/chat/completions` endpoint on a schedule to write the
  two sentences a fraud analyst reads next to a `REVIEW`.
- `NarratorProperties` (`risk.narrator.url / model / apiKey / timeout /
  maxTokens / maxAge`) is exactly the OpenAI-compatible client config shape.
- It already does the production-grade bits: read timeout, bearer-token header,
  `<think>`-block stripping for local reasoning models, a **deterministic
  template fallback** when the model is unreachable, and an age cutoff so a
  backlog never pins the GPU for hours.

What the Narrator does **not** yet do, and what the roadmap introduces:
structured/JSON output, retrieval/embeddings, tool/function calling, streaming,
and any LLM output that feeds a write.

So Phase 0 lifts the Narrator's proven client into a small shared pattern; the
later phases each add exactly one new LLM capability on top.

## Governing principles (generalised from ADR 0005)

ADR 0005 already decided how a model lives in this system. These extend
unchanged to every LLM feature and are the acceptance bar for each phase:

1. **Beside the path, never on it.** An LLM call is async or read-only. Its
   latency and its outage must never stall or fail a payment. Nothing on the
   money path waits on a token.
2. **The LLM never moves money.** Output is either a read-only answer or a
   *proposal* a human or a deterministic rule approves. Every mutation still
   goes through the existing command + auth (Keycloak, step 31) + idempotency
   (step 11) path. The LLM holds no credential that can move money.
3. **Always a deterministic fallback.** Model unreachable or output invalid →
   the feature degrades to a template/empty state, as the Narrator already does.
   A missing sentence embarrasses a dashboard, never a payment.
4. **Validate structured output before use.** In a financial context we never
   act on free text. Responses use JSON/`json_schema` and are parsed into a
   record and validated; a parse failure is a fallback, not an exception the
   user sees.
5. **Same two-guard isolation.** Any AI service keeps ADR 0005's two
   independent locks: a Postgres role that cannot `CONNECT` to another
   service's DB, and an `ArchitectureTest` forbidding imports of another
   service's domain / `OutboxAppender` / `KafkaTemplate`.
6. **Local data boundary.** Ledger data goes only to the local agent platform.
   No hosted provider without an explicit decision.
7. **Every LLM feature ships an eval set + a trace + a token metric.** A small
   golden-case file per feature (input → expected shape/content), one tracing
   span around the LLM call (reuse step 14 observability), and a token counter.

## Phase 0 — shared LLM client (foundation)

**Goal:** one proven way to call the agent platform, so phases 1–4 add features,
not plumbing.

- Lift the Narrator's `RestClient` → `/chat/completions` call, timeout,
  bearer header, `<think>` stripping and template-fallback into a thin shared
  support piece (`ledgerflow-llm`: one `ChatClient` wrapper, one
  `LlmProperties` record, one validated-JSON helper). Reuse Spring `RestClient`
  and `tools.jackson` — both already in the tree. **No framework.**
- Add what the Narrator lacks: `response_format` structured output (JSON
  confirmed working), a streaming variant, and generous default `max_tokens`
  for the reasoning model. A native tool-calling request shape is deferred —
  the proxy rejects it today (see Probed), so phases 1/4 use JSON-dispatch.
- One eval-harness skeleton (golden cases → assert) and the tracing span +
  token metric wrapper.

**New pattern:** structured output, tool-call request shape.
**Reuse:** Narrator client, RestClient, Jackson, step 14 observability.
**Done-when:** a service can ask the platform a question and get a validated
JSON record back, with a passing fallback when the platform is stopped.
**Size:** small (~3 files + config).

**Shipped (2026-11-06) — `libs/ledgerflow-llm`:**
- `LlmClient` — the Narrator's `RestClient` / timeout / bearer / `<think>`-strip,
  plus `chat()` free-text, `chatJson(…, Class<T>)` validated structured output
  (`response_format: json_object`, parsed into a record), and model discovery
  (`GET /v1/models`, prefer `llm.model`, match tag variants so `gemma4` →
  `gemma4:e4b`, else first offered).
- `LlmProperties` (`llm.*`: url / model / apiKey / timeout / maxTokens=2048).
- `LlmException` — any outage / empty completion / unparseable JSON fails loud;
  the client never invents a reply, the **feature** owns the template fallback
  (keeps ADR 0005 principle 3 at the call site, where the template lives).
- Self-check: `LlmClientTest` (3 cases, `MockRestServiceServer`) — discovery +
  json_object dispatch + parse, bad-JSON → `LlmException`, empty-content → loud.

**Deferred (YAGNI until a phase consumes it):** streaming variant → Phase 1
(the web UI is its first consumer); eval-harness skeleton + tracing span + token
metric → fold into Phase 1's eval/demo rather than scaffold an empty harness now.
The client is not yet wired into any service (no `@Bean`); Phase 1 adds the first.

## Phase 1 — natural-language ledger assistant (read-only) · the hero demo

**Goal:** "Ask the ledger anything." A user asks in English
("how much did we refund last week?", "which organisers have the most
REVIEWs?") and gets a grounded answer.

- Read path only. Lives behind the gateway/web (step 33, ADR 0008) as a small
  `insights` endpoint (extend web or a tiny read-only service — see Open inputs).
- **JSON-dispatch over a fixed set of parameterised read-model query tools**
  (e.g. `refund_total(period)`, `orders_by_status(status)`,
  `review_cases(window)`). The LLM emits, as validated JSON, *which* whitelisted
  query to run with *which* params; we execute it deterministically and feed the
  rows back for a phrased answer. The LLM **never** sees or emits SQL — the
  whitelist is the injection guard — and never touches the DB itself. Swaps to
  native tool-calling transparently if a tool-capable model appears.
- RAG over the event catalog (`docs/events/*`) is now **possible** — embeddings
  work through the platform (ollama + `nomic-embed-text`). But for Phase 1 the
  catalog is small enough to stuff into the prompt, so RAG stays optional and
  earns its place only once there's a real corpus to retrieve over.
- Stream the answer to the web UI.

**New pattern:** JSON-dispatch tool selection, streaming. (RAG deferred.)
**Reuse:** CQRS read models (step 13), gateway + web (step 33), Phase 0 client.
**Guardrail:** whitelisted parameterised query tools only; results are
authoritative, the LLM phrases. No free SQL, no writes.
**Eval/demo:** golden Q→A set over a seeded ledger; live demo in the web UI.
**Done-when:** English question → correct number/list from the read models,
phrased, streamed; a bad/unknown question declines instead of inventing.
**Size:** medium. Highest demo value, lowest blast radius.

**Shipped (2026-11-06) — gateway backend, `io.ledgerflow.gateway`:**
- `POST /api/v1/insights` (`AssistantController`) — streams the answer as SSE; needs the
  `account-read` staff role, which it relays downstream.
- `LedgerAssistant` — `chatJson` dispatch → whitelist guard → run tool → `chatStream`
  the phrasing. The LLM chooses a read and phrases rows; it never emits SQL/URL, never writes.
- `QueryCatalog` — the injection guard: 3 tools (`review_cases`, `orders_by_status`,
  `recent_payments`) each a GET to the gateway's own routed read endpoint, params
  whitelisted (status validated against the order-status set, limit clamped 1–200, unknown
  params dropped). The assistant self-calls the gateway with the caller's bearer, so auth is
  re-checked downstream and no other service's domain is imported (ADR 0005 principle 5 holds
  for a service with no DB).
- `LlmClient.chatStream` — the Phase 0 deferred streaming variant, landed here (its first consumer).
- Config `llm.*` + `insights.gateway-base`; blank `LLM_URL` → the stream says the assistant is
  unavailable and completes, never a 500 (principle 3). Guard unit-tested (`QueryCatalogTest`,
  5 cases); golden Q→A set in `docs/ai/evals/phase1-insights.md`.

**Deferred:** the React demo page in `web` (separate stack — the API is demoable over curl SSE
meanwhile); `refund_total(period)` (no backing read endpoint yet — add with Phase 3's enriched
projection, or a refund-total endpoint, rather than a cross-service aggregate in the gateway);
live golden-eval run (needs the platform binary rebuilt per Probed #2 and the stack up).

## Phase 2 — risk narration v2 + case investigation (extend `risk-service`)

**Goal:** turn the existing naive Narrator into a production feature, and let an
analyst interrogate a case. This covers the "anomaly" ask — **detection already
exists** (the ONNX `risk-lr-v1` model + `Rules`); the AI adds explanation and
investigation, it never makes the decision.

- Upgrade `Narrator` to **structured output**: JSON `{ whatIsUnusual,
  whatToCheck, confidence }` instead of two free sentences, validated before
  the `risk_case` insert. Keep the batch schedule and the template fallback.
- Add an interactive endpoint on `CasesController`: an analyst asks a follow-up
  ("why is this beneficiary flagged?") and the LLM answers from that decision's
  own `risk_decision` row + feature window — nothing else.

**New pattern:** per-request interactive LLM on top of the existing scheduled
batch; structured output on an existing feature (a clean before/after story).
**Reuse:** `Narrator`, `CasesController`, `risk_decision`, Phase 0 client.
**Guardrail (ADR 0005, unchanged):** only a rule can `BLOCK`, only the model
can `REVIEW`. The LLM explains a decision already made; nothing downstream reads
it back. The LLM never decides.
**Done-when:** a `REVIEW` gets a validated JSON case note; an analyst follow-up
answers from the record only; platform down → template, as today.
**Size:** small–medium (one service, mostly extending existing code).

**Shipped (2026-11-06) — `risk-service`:**
- `Narrator` now asks for `CaseNote {whatIsUnusual, whatToCheck, confidence}` via
  `LlmClient.chatJson` (reuses the Phase 0 client — its own `RestClient`/`ChatRequest`/
  `<think>`-strip deleted). `render` validates before the insert (both sentences non-blank,
  confidence clamped 0..1) and returns null → template on any invalid/unreachable reply.
  New nullable `risk_case.confidence` column (Flyway `V3`); batch schedule + template fallback kept.
- `CaseInvestigator` + `POST /api/v1/cases/{paymentId}/ask`: an analyst asks a follow-up and the
  LLM answers from that one `risk_decision` row only — never fetches more, never recommends
  block/approve (the decision is already made), outage → "unavailable".
- Config migrated: the client config moved from `risk.narrator.{url,model,api-key,max-tokens}`
  to the shared `llm.*`; `risk.narrator` keeps only the batch knobs (`batch`, `maxAge`).
- `ArchitectureTest` still green (the lib is `io.ledgerflow.llm`, outside the forbidden packages).
  Tests: `NarratorRenderTest` (validate/compose/clamp/fallback) + full suite 17/17.

**Not verified live** (same reason as Phase 1): host stack down + platform binary rebuild pending.
Against raw ollama a reasoning model may wrap the JSON and trip the parse → template; the real
target is the proxy, where reasoning is split out of `content`. The fallback covers both.

## Phase 3 — event-stream enrichment / categorisation (async projection)

**Goal:** enrich payments/refunds with an LLM-derived category + one-line reason
summary, so Phase 1's assistant can answer "break refunds down by reason".

- A new async consumer (own service, or extend a read-model owner) consumes
  payment/refund events, produces `{ category, reasonSummary }` as validated
  JSON, and projects it to a new read-model table. Pure async — the ADR 0005
  "score the stream, only record" shape.
- Phase 1's query tools gain access to the enriched fields — closes the loop.

**New pattern:** async LLM enrichment feeding a CQRS projection.
**Reuse:** existing event stream + listener pattern, Phase 0 JSON output,
Phase 1 query tools.
**Guardrail:** enrichment is a read-model only; a wrong tag embarrasses a
dashboard, never a payment. Idempotent per event (step 11 pattern).
**Done-when:** events get categorised into the projection; the assistant can
group by category; replaying an event doesn't double-write.
**Size:** medium.

**Decision (roadmap "Still open" #4 — enrichment owner): extend `payment-service`,
no new service.** It already owns the payments read model (`/api/v1/payments`,
the `recent_payments` tool) and already consumes refund traffic, and it has the
step-11 `Inbox` idempotency wired. A new service would mean a new Postgres role,
a new pod and a new Keycloak-less chain for a read model that lives next to data
payment-service already holds — the "only if nothing fits" case, and one fits.
So no new `ArchitectureTest`/role (those guard a *new* service, ADR 0005 #5);
payment-service keeps its existing isolation.

**Shipped (2026-11-06) — `payment-service`:**
- `PaymentOutcomeEnrichmentListener` reads the outcome topic the saga already
  publishes (`ledgerflow.payment.outcome.events.v1` — `PaymentCaptured` /
  `PaymentRefunded` / `PaymentFailed` share it) and tags the two terminal events
  worth recording. `PaymentFailed` is ignored (nothing settled to categorise).
- `Enrichment` asks the Phase 0 `LlmClient.chatJson` for a `Tag {category,
  reasonSummary}`, validates it (`render`: lower-cases + trims + clamps 64/280,
  invalid → null), and projects to the new `payment_enrichment` read-model table
  (Flyway `V6`, key `(payment_id, kind)` so a payment and its later refund each
  get a row). The LLM call is **before** `Inbox.once`, never inside its dedupe
  transaction — a Kafka consumer thread must not hold a DB row waiting on a token.
- Idempotent two ways: `Inbox.once` claims `(event_id, group)` and the insert is
  `ON CONFLICT (payment_id, kind) DO NOTHING`, so a replayed event never
  double-writes. Platform unreachable / blank `LLM_URL` / unparseable output →
  the row still lands tagged `uncategorised`; the stream never stalls on a token
  (ADR 0005 #1, #3, #4).
- Phase 1 loop closed: gateway `QueryCatalog` gains the whitelisted
  `category_breakdown` tool (param `kind` ∈ {payment, refund}, validated) → the
  new `GET /api/v1/payments/breakdown` read endpoint, which groups by category
  with counts + totals. The assistant can now answer "break refunds down by
  category". Config `llm.*` (blank url by default).
- Tests: `EnrichmentRenderTest` (validate/lowercase/truncate/fallback),
  `QueryCatalogTest` +2 (kind whitelist + unknown-kind reject),
  `PaymentEnrichmentIT` (captured + refunded project `uncategorised`, replay of
  an identical envelope stays one row, `PaymentFailed` not projected).

**Deferred (known ceiling):** the tag signal is thin — the outcome event carries
only amount + kind, not the `beneficiary` (dropped after the `Requested` state),
so categories are coarse. Richer tags would mean joining `PaymentRequested` over
the stream (a second listener + correlation), deferred until a demo needs it.
Live categorisation not run (host stack + platform down, same as phases 0–2);
proven by the IT's fallback path + unit tests. This also partly answers Phase 1's
deferred `refund_total(period)`: `category_breakdown(kind=refund)` gives per-
category refund counts and totals.

## Phase 4 — agentic ops assistant with human-in-the-loop writes · capstone

**Goal:** the highest-value and highest-risk feature, deliberately last. The
assistant can look up an order/payment and **propose** a mutating action — e.g.
a refund on a cancelled order — which a human approves before anything fires.

- Reuses Phase 1's read tools (JSON-dispatch), plus a **proposal** step that
  emits a structured command as validated JSON (not an executed one).
- The proposal is rendered for a human to approve. On approval it fires through
  the **existing step-35 refund command path**, with the existing Keycloak auth
  (step 31) and idempotency (step 11), and a full audit row.

**New pattern:** JSON-dispatch that produces a proposed mutation; two-key
execution (human approval + the same server-side validation the API already
enforces).
**Reuse:** refund command (step 35), auth (step 31), idempotency (step 11),
Phase 1 read tools.
**Guardrail:** the LLM emits a *proposed command*, never an executed one, and
has no credential of its own. Nothing moves until a human approves and the
server re-validates exactly as it would for a human-issued request.
**Done-when:** the assistant proposes a correct refund command for a cancelled
order; it fires only after approval; a rejected/edited proposal never fires; an
approved one is idempotent and audited.
**Size:** large. Build only after 0, 1 and the approval UI exist.

**Shipped (2026-11-07) — gateway propose + ticketing fire, two separate keys:**
- **Propose (read-only).** `POST /api/v1/insights/propose` (`AssistantController`) →
  `LedgerAssistant.propose`: reuses Phase 1's read dispatch to pull candidate orders, then one
  `chatJson` asks for a single `refund_order` command. `LedgerAssistant.validate` (pure, static)
  is the guard — only a `refund_order` with a well-formed UUID survives; anything else, including
  a bad/missing id, becomes a decline. The LLM emits a *proposed* command and nothing more; the
  gateway runs no write. `account-read` role (an analyst may propose); platform down → 503, never
  an invented proposal (principle 3). Config reuses Phase 1's `llm.*` + `insights.gateway-base`.
- **Fire (ledger-write).** `POST /api/v1/orders/{id}/refund` (`OrdersController`) →
  `Ticketing.refundOrder`: publishes the **same `RefundOrder` command cancelEvent sends per order**
  (the step-35 path) via the outbox, for a CONFIRMED order with a payment. The firing token is the
  **human's own** `ledger-write` bearer, re-checked by Keycloak (step 31) at the controller and
  re-validated by ticketing (CONFIRMED-only) — the two keys are a human role the LLM never holds
  plus the server-side validation a hand-issued refund already passes. Idempotent: `payment.refund`
  moves a Captured payment once (step 11 inbox on the listener), so a double fire is a no-op and a
  re-fire of a now-REFUNDED order is 409. Audit: a `log.info` naming actor + order + payment on
  every money move (the repo's audit style — log + metric, no audit table).
- Nothing new on the gateway's isolation: propose reaches orders/payments over the gateway's own
  routed read API with the caller's bearer (no DB, no domain import — ADR 0005 #5 holds), and the
  fire is a plain routed `POST /api/v1/orders/**` the gateway already proxies to ticketing.
- Tests: `ProposalTest` (5 cases — the gateway guard: valid refund survives + trims, blank reason
  defaulted, non-UUID declines, unknown action declines keeping the model's reason, null→default);
  `TicketingFlowIT.refundOrderFiresTheStep35CommandForOneConfirmedOrder` (one RefundOrder for a
  CONFIRMED order, refund settles it REFUNDED, HELD→409, unknown→404). Golden set +
  two-key/platform-down checks in `docs/ai/evals/phase4-actions.md`.

**Verified live (2026-11-07, docker compose — the running stack is compose, not kind):** gateway +
ticketing rebuilt and redeployed. The full money path proven end-to-end against the live stack: a
seeded order holds → checkout → **CONFIRMED**, `POST /insights/propose` degrades to **503** with
`LLM_URL` blank (principle 3, no platform wired into compose), `POST /orders/{id}/refund` as
ledger-write returns **202** and the order settles **CONFIRMED → REFUNDED** down the step-35 command
path; a replay of the fire is **409** (idempotent, already refunded) and a reader (account-read, no
write) firing is **403**. Two deploy-time fixes were needed and are in this change: the gateway's
Kafka consumer config was mis-nested under `insights.kafka` (orphaned — not a Spring property), so
the stream listener booted with no `group.id` and crash-looped; moved to `spring.kafka`. Known gap:
`/insights/propose` for an account-read-only caller currently returns 403, not 503 — the
gateway-MVC security chain isn't matching the sub-path `requestMatchers` (it matches the parent
`/insights`), so propose effectively needs ledger-write. Conservative (only a would-be approver
proposes) and harmless to the money path; the ops demo user holds both roles. Tracked for a proper
matcher fix.

**Deferred:** wiring the agent platform into compose (host-only at `127.0.0.1:18410` + a per-env
bearer), so propose/ask run a real model rather than degrading — until then the UI shows the
"unavailable" path live. No proposal store: the proposal is stateless, the client holds it and fires
the orderId; the fire re-validates server-side regardless, so a store would buy nothing (YAGNI).

## Why this order

- **Phase 1 first:** biggest portfolio impact, fully read-only, introduces the
  three patterns (tool calling, streaming, structured output) the rest reuse.
- **Phase 2 next:** cheapest new value — extends a service that already calls an
  LLM; strong "productionised a naive feature" story.
- **Phase 3:** pure async, no user-facing latency, feeds Phase 1.
- **Phase 4 last:** only feature that touches money; safe only once validated
  JSON, read tools and an approval surface already exist.

## The agent platform (resolved)

- **Base URL:** `http://127.0.0.1:18410`, OpenAI-compatible under `/v1`
  (`/v1/chat/completions`, `/v1/models`, `/v1/embeddings`). This is a proxy
  ("agent-platform"), not raw Ollama — it fronts providers and workspaces.
- **Auth is required** (not blank locally): `Authorization: Bearer <token>`
  where the token is a session JWT, an `agp_` workspace token, or the master
  key. So the `apiKey` prop is **mandatory**, carried per environment, never
  committed. `GET /health` reports auth with no token; `401 AUTH_REQUIRED`
  = header missing, `TOKEN_EXPIRED` = refresh at
  `POST /accounts/api/v1/auth/refresh`, `TOKEN_INVALID` = wrong secret.
- **Model selection is dynamic.** Models are not all present at once, so the
  client **queries `GET /v1/models`** and picks; default to **`gemma4`** when
  it exists / the provider is ollama. `llm.model` becomes a *preferred* model
  with a discovery fallback, not a hardcoded string — a small addition to
  Phase 0.

### Probed 2026-11-06 (one model live: `gemma4:e4b`, ollama)

- **Chat** ✅ standard OpenAI shape, `usage` token counts returned.
- **JSON output** ✅ `response_format: {type:"json_object"}` returns valid JSON
  in `content`. `json_schema` not yet confirmed.
- **Reasoning model.** `gemma4:e4b` returns a separate `reasoning` field and
  spends tokens thinking before it answers — so `max_tokens` must be generous
  (a small budget hit `finish_reason:"length"` with empty `content`). Via this
  proxy the reasoning is split out of `content`, so the Narrator's `<think>`
  strip is unnecessary here (harmless if kept).
- **Embeddings** ✅ fixed in the platform (2026-11-06). The proxy had
  `capability_unavailable` for `provider: ollama` because ollama's
  `ProviderSpec` declared only `[Chat, VisionInput]` — a wrong gate, since
  ollama serves `/v1/embeddings` for an embedding model (`nomic-embed-text`
  verified on `:11434`). Fixed by declaring `Embeddings` on ollama, matching
  LM Studio (agent-platform commit `cf603b1`, `cargo test --lib`: 346 pass).
  Takes effect once the running platform binary is rebuilt + restarted. RAG is
  then reachable through the proxy with `nomic-embed-text`, no second endpoint.
- **Native tool-calling** ⚠️ the proxy rejected a well-formed `tools` request
  twice (`bad_request`). Treat native function-calling as **not available** on
  this model/provider path until proven. Phases 1 and 4 therefore use
  **JSON-dispatch** instead (below), which needs only the proven
  `json_object` mode — and is the safer design regardless.

## Still open (not blocking the plan)

1. ~~A bearer token~~ — in hand; chat + JSON smoke-tested against
   `gemma4:e4b`. The token is a secret, carried via the `apiKey` prop /
   environment, never committed.
2. ~~Embeddings route for RAG~~ — resolved. `nomic-embed-text` is pulled and
   the platform now serves embeddings through the proxy for ollama (fix above),
   so RAG rides the one authenticated endpoint. Still not needed for Phase 1
   (prompt-stuff the small catalog); the only remaining step is rebuilding the
   running platform binary so the fix is live.
3. **`insights` as a new service vs. an endpoint on web/gateway** (Phase 1) —
   lean to an endpoint first, promote to a service only if it earns isolation.
4. ~~**Enrichment owner** (Phase 3)~~ — resolved: extend `payment-service` (it
   already owns the payments read model + refund traffic + the step-11 inbox). A
   new service would add a role/pod/chain for nothing. See Phase 3's Decision.

## Not doing yet (YAGNI)

- No hosted LLM provider, no key management beyond the existing `apiKey` prop.
- No fine-tuning / training — detection stays the ONNX model (ADR 0005).
- No agent memory / multi-turn session store until a phase needs it.
- No letting any LLM output reach a write path without the Phase 4 approval gate.
