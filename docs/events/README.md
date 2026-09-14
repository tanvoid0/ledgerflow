# What is wrong with our first event (written at step 07, ticked off as steps land)

Seen on the wire at step 07, one hold request with two wallets:

```
key:     240ae386-8101-419a-8928-2b422b8c1cf0
value:   {"holdId":"240ae386-…","accountId":"11111111-…","wallets":["A-12","A-13"],"expiresAt":"2026-09-14T19:59:14Z"}
headers: __TypeId__ = io.ledgerflow.ledger.domain.event.FundsHeld
```

Contracts, one file per event: `ledger.FundsHeld.md`.

1. Published AFTER the transaction committed. If the broker is down, the hold
   exists and nobody will ever hear about it.                     -> step 10
2. The event class is defined twice, once in each service. They will drift.
   The `__TypeId__` header already names a class notification does not have;
   the consumer only works because it ignores the header.         -> step 08 DONE
   (one record in ledgerflow-events, a JSON schema next to it, a registry gate)
3. The topic name says nothing about who owns it or which version it is.
                                                                  -> step 08 DONE
   (`ledgerflow.ledger.wallet-hold.events.v1`, keyed by hold id)
4. No eventId, so a consumer cannot tell a redelivery from a new event.
                                                                  -> step 08 DONE (the id), step 11 (using it)
5. The listener's offset commits as soon as it returns, and it returns whether
   or not the email was actually sent. A crash inside the handler redelivers;
   a handler that swallows its own failure loses the work silently.
                                                                  -> step 09 DONE
   (manual_immediate, `ack.acknowledge()` after the work; measured in
   `docs/measurements/step-09-consumer-restart.md`)
6. A message the listener throws on is retried 10 times back to back, then
   logged and skipped. Not forever: worse. Gone.                  -> step 09 DONE
   (three non-blocking retries on `.retry-*` topics, then `.dlt`; poison
   pills skip the retries)
7. One request placed two holds but published one event carrying the first
   hold's id. The other hold is invisible to every listener.      -> step 08 DONE (one event per hold)
8. No amount on the event. A notification that cannot say "we held GBP 45.00"
   is not much of a notification.                                 -> step 08 DONE (`totalAmount`)
