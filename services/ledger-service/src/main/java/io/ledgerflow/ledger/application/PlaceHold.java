package io.ledgerflow.ledger.application;

import io.ledgerflow.events.EventEnvelope;
import io.ledgerflow.events.Money;
import io.ledgerflow.events.WalletRef;
import io.ledgerflow.events.ledger.FundsHeld;
import io.ledgerflow.ledger.adapter.out.account.AccountGateway;
import io.ledgerflow.ledger.domain.model.FundsHold;
import io.ledgerflow.ledger.domain.model.UnknownWalletException;
import io.ledgerflow.starter.messaging.OutboxAppender;
import io.ledgerflow.starter.web.RequestIdFilter;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PlaceHold {

    private static final Duration HOLD_FOR = Duration.ofMinutes(10);

    private final AccountGateway account;
    private final FundsHoldRepository holds;
    private final OutboxAppender outbox;
    private final TransactionTemplate tx;

    /**
     * Ledger must not hold a wallet that account has never heard of: hence the network call.
     * Deliberately not @Transactional on the method: a transaction here would pin a database
     * connection for the whole of the wait on account, and a slow account would drain the pool
     * as well as the thread pool. The write is its own short transaction, and the event goes
     * into the outbox inside it: if the hold rolls back, so does the event.
     */
    public List<FundsHold> place(UUID accountId, List<String> walletCodes, Money amount) {
        var accountView = account.accountOrLastKnown(accountId);

        for (var label : walletCodes) {
            if (!accountView.hasWallet(label)) throw new UnknownWalletException(accountId, label);
        }

        var expiry = Instant.now().plus(HOLD_FOR);
        return tx.execute(status -> {
            var placed = holds.saveAll(walletCodes.stream()
                    .map(label -> FundsHold.hold(accountId, label, amount, expiry))
                    .toList());
            placed.forEach(hold -> outbox.append(FundsHeld.TOPIC, fundsHeld(hold)));
            return placed;
        });
    }

    /** One event per hold, keyed by the hold id: everything about one hold lands on one partition, in order. */
    private static EventEnvelope<FundsHeld> fundsHeld(FundsHold hold) {
        // the HTTP request id is both: it started the story, and it directly caused this event
        var requestId = MDC.get(RequestIdFilter.MDC_KEY);
        var payload = new FundsHeld(hold.id(),
                List.of(new WalletRef(hold.accountId(), hold.walletCode())),
                hold.expiresAt(), hold.amount());
        // aggregateVersion 1: placing a hold is the first thing that ever happens to it
        return EventEnvelope.of(FundsHeld.TYPE, hold.id(), 1, requestId, requestId, payload);
    }
}
