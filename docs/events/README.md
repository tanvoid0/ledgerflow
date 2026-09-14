# What is wrong with our first event (written at step 07)

Seen on the wire, one hold request with two wallets:

```
key:     240ae386-8101-419a-8928-2b422b8c1cf0
value:   {"holdId":"240ae386-…","accountId":"11111111-…","wallets":["A-12","A-13"],"expiresAt":"2026-09-14T19:59:14Z"}
headers: __TypeId__ = io.ledgerflow.ledger.domain.event.FundsHeld
```

1. Published AFTER the transaction committed. If the broker is down, the hold
   exists and nobody will ever hear about it.                     -> step 10
2. The event class is defined twice, once in each service. They will drift.
   The `__TypeId__` header already names a class notification does not have;
   the consumer only works because it ignores the header.         -> step 08
3. The topic name says nothing about who owns it or which version it is.
                                                                  -> step 08
4. No eventId, so a consumer cannot tell a redelivery from a new event.
                                                                  -> steps 08, 11
5. Consumer offsets are auto-committed, so a crash mid-processing loses work.
                                                                  -> step 09
6. A message the consumer cannot handle will be retried forever.  -> step 09
7. One request placed two holds but published one event carrying the first
   hold's id. The other hold is invisible to every listener.      -> step 08
8. No amount on the event. A notification that cannot say "we held GBP 45.00"
   is not much of a notification.                                 -> step 08
