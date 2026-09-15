# Step 14: one payment, one trace, seven services

`grafana/otel-lgtm` in compose (Grafana on 3000, OTLP in on 4318), seven services on the host
pushing traces, metrics and logs to it. Sampling 1.0. Every span below is from Tempo's own
trace API, ordered by start time, indented by parent.

## A payment that captured

`POST /api/v1/payments` for 45.00 on A-14: 48 spans, 3.2s wall clock, every hop on one id.
HTTP client spans and `send` spans folded out for width.

```
    0ms http post /api/v1/payments                                   payment-service
   50ms   outbox ledger.ReserveWallets                                payment-service
  412ms     publish ledger.ReserveWallets                             payment-service   <- the poller, on its own thread
  456ms         ledgerflow.ledger.hold.commands.v1 process            ledger-service
  510ms             http get /api/v1/accounts/{id}                    account-service
  605ms           outbox ledger.FundsHeld                             ledger-service
 1094ms             publish ledger.FundsHeld                          ledger-service
 1130ms                 ledgerflow.ledger.wallet-hold.events.v1 process   payment-service
 1140ms                 ledgerflow.ledger.wallet-hold.events.v1 process   balance-service
 1387ms                   evalsha                                     balance-service
 1149ms                   outbox issuer.AuthorizePayment              payment-service
 1458ms                     publish issuer.AuthorizePayment           payment-service
 1474ms                         ledgerflow.issuer.authorization.commands.v1 process   issuer-service
 1492ms                           outbox issuer.PaymentAuthorized     issuer-service
 1612ms                             publish issuer.PaymentAuthorized  issuer-service
 1640ms                                 ledgerflow.issuer.authorization.events.v1 process   payment-service
 1646ms                                   outbox settlement.IssueCaptures              payment-service
 1971ms                                     publish settlement.IssueCaptures           payment-service
 1988ms                                         ledgerflow.settlement.capture.commands.v1 process   settlement-service
 2017ms                                             http get /api/v1/accounts/{id}    account-service
 2035ms                                             http post /api/v1/transfers       account-service
 2062ms                                               outbox account.EntryPosted      account-service
 2080ms                                           outbox settlement.CapturesIssued    settlement-service
 2123ms                                                 publish account.EntryPosted   account-service
 2351ms                                                     ledgerflow.account.entry.events.v1 process   balance-service
 2331ms                                             publish settlement.CapturesIssued settlement-service
 2363ms                                                 ledgerflow.settlement.capture.events.v1 process   payment-service
 2370ms                                                   outbox ledger.CaptureHolds  payment-service
 2484ms                                                     publish ledger.CaptureHolds   payment-service
 2491ms                                                         ledgerflow.ledger.hold.commands.v1 process   ledger-service
 2635ms                                                           outbox ledger.HoldClosed   ledger-service
 3147ms                                                             publish ledger.HoldClosed   ledger-service
 3156ms                                                                 ledgerflow.ledger.hold-closed.events.v1 process   balance-service
 5152ms                 ledgerflow.ledger.wallet-hold.events.v1 process   notification-service
```

Where the time goes is now a fact rather than a guess: of 3.2s, about 2.4s is waiting for
outbox pollers (`outbox X` to `publish X`, up to 500ms each, seven of them on the critical
path). The broker itself is ~10ms a hop, the services single-digit ms. Step 15 knows what
to shorten.

## A payment that was declined

`amountMinor: 1`, the issuer says no. 31 spans; the compensation is in the same tree:

```
    0ms http post /api/v1/payments                                   payment-service
    2ms   outbox ledger.ReserveWallets                                payment-service
  110ms         ledgerflow.ledger.hold.commands.v1 process            ledger-service
  121ms           outbox ledger.FundsHeld                             ledger-service
  288ms                 ledgerflow.ledger.wallet-hold.events.v1 process   payment-service
  291ms                   outbox issuer.AuthorizePayment              payment-service
  621ms                         ledgerflow.issuer.authorization.commands.v1 process   issuer-service
  623ms                           outbox issuer.PaymentDeclined       issuer-service
  780ms                                 ledgerflow.issuer.authorization.events.v1 process   payment-service
  786ms                                   outbox ledger.ReleaseWallets               payment-service   <- the compensation
 1133ms                                         ledgerflow.ledger.hold.commands.v1 process   ledger-service
 1138ms                                           outbox ledger.HoldClosed           ledger-service
 1303ms                                                 ledgerflow.ledger.hold-closed.events.v1 process   balance-service
```

The metric moved with it: `ledgerflow_saga_compensated_total{step="AUTHORIZE",reason="PAYMENT_DECLINED"} 1`.

## From the trace to the logs, and back

Every line goes to Loki through the OTel logback appender with `trace_id`, `span_id` and the
MDC (`requestId`, `paymentId`) as structured metadata. The two queries the step asks for:

```
{service_name=~".+"} | trace_id="3c5e0faeecbc9f36cf44a5aa9220a54b"          -> 44 lines, 6 services, in order:
    payment-service     b3f32c3b a6297773  payment a6297773-…: Requested + FundsHeld -> AuthorizationPending
    issuer-service      b3f32c3b -         authorized payment a6297773-… as 14af43c6-…
    payment-service     b3f32c3b a6297773  payment a6297773-…: AuthorizationPending + PaymentAuthorized -> CapturePending
    settlement-service  b3f32c3b -         captured 1 hold(s) for payment a6297773-…
    payment-service     b3f32c3b a6297773  payment a6297773-…: CapturePending + CapturesIssued -> Captured
    ledger-service      b3f32c3b -         1 hold(s) for a6297773-… now CAPTURED

{service_name="payment-service"} | paymentId="8c547f00-…"                   -> the declined one, both transitions
```

`requestId` is the `X-Request-Id` the POST answered with; it reaches ledger, issuer and
settlement because the inbox puts the event's `correlationId` on the MDC before running the
listener, so nobody has to remember to. balance-service logs nothing on the happy path and
uses no inbox; its lines carry the trace id only.

## Metrics that were there for free, and the two that were not

Pushed over OTLP every minute, in Prometheus under the service name:

| metric | from | worth alerting on |
|---|---|---|
| `kafka_consumer_fetch_manager_records_lag_max{service_name}` | the Kafka client, via Micrometer's binder | `> 1000 for 5m` -> page: the consumer cannot keep up |
| `ledgerflow_dead_letters_total{topic}` | `DeadLetters.onDead` (messaging starter) | `increase(…[10m]) > 0` -> page: a message was abandoned. Zero is the only acceptable value |
| `ledgerflow_saga_compensated_total{step,reason}` | `Payments.apply`, on every transition to Failed | `rate(…[15m])` above the baseline -> investigate: customers are being turned away, and the tag says why |
| `http_server_requests_seconds`, `outbox_append_seconds`, `outbox_publish_seconds` | Boot, and the two outbox observations | latency; step 15 |

Proof of the first two: a poison record on the hold topic dead-lettered in all three of its
consumers within 15s, `ledgerflow_dead_letters_total` went to 1 for each of
`…notification-service.dlt`, `…balance-service.dlt`, `…payment-service.dlt`. A depth gauge
(what `scripts/dlt-depth.sh` reports) would need a scheduled AdminClient poll; a counter at the
handler is one line and alerts the same.

## What the step's two config lines do not do

`spring.kafka.template.observation-enabled` and `spring.kafka.listener.observation-enabled` carry
the trace across the broker: the template writes `traceparent` into the record headers, the
listener reads it. In this project that was not enough, and every trace ended at the outbox.
The send happens in `OutboxPublisher.drain()`, on a scheduler thread, up to 500ms after the
request that wrote the row has returned; the template's span had no parent and every hop
started a new trace.

The fix is a column: `OutboxAppender.append` runs inside a Micrometer `SenderContext`
observation, so the propagator writes the current span into a map that is stored as
`outbox.trace_context`; `drain()` wraps each send in a `ReceiverContext` observation built from
that map, so the template's span hangs off the request. That is what the `outbox X` and
`publish X` spans above are. `PaymentFlowIT` reads the command back off the wire and checks its
`traceparent` against the trace that started the payment.

Two Boot 4 facts that cost an hour: `@SpringBootTest` disables tracing export, and Boot then also
replaces the propagator with a no-op, so a propagation test needs `@AutoConfigureTracing`
(export on) with `management.tracing.export.otlp.enabled=false` (but not to anywhere). And Boot
exports OTel logs but does not feed them: the logback appender is a third-party jar, attached
in `LogShippingAutoConfiguration`, only where a `SpanExporter` exists, so the test JVMs do not
retry against a closed port once a second.
