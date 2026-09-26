package io.ledgerflow.account.application;

import io.ledgerflow.account.domain.model.JournalEntry;
import io.ledgerflow.events.EventEnvelope;
import io.ledgerflow.events.Money;
import io.ledgerflow.events.account.EntryPosted;
import io.ledgerflow.starter.messaging.OutboxAppender;
import io.ledgerflow.starter.web.RequestIdFilter;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.resilience.annotation.ConcurrencyLimit;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PostTransfer {

    private final LedgerRepository ledger;
    private final OutboxAppender outbox;

    /**
     * No read-then-decide anywhere. The debit is one conditional UPDATE; the row lock
     * Postgres takes for it is what serialises two racing requests, and the CHECK
     * constraint on the column catches anything that ever gets past it.
     */
    @PreAuthorize("hasRole('ledger-write')")
    @ConcurrencyLimit(10)   // = Hikari's default pool; callers past it queue in the JVM (ThrottlePolicy.BLOCK is the default)
    @Transactional
    public JournalEntry transfer(String idempotencyKey, UUID from, UUID to, Money amount, String description) {
        if (amount.minorUnits() <= 0) throw new IllegalArgumentException("amount must be positive");

        // A retry: answer from the book. The UNIQUE index is the guarantee; this lookup saves the write.
        var replay = ledger.findByIdempotencyKey(idempotencyKey);
        if (replay.isPresent()) return replay.get();

        var debited = ledger.debitIfSufficient(from, amount).orElseThrow(() -> new InsufficientFundsException(from));
        var credited = ledger.credit(to, amount);

        var entry = JournalEntry.transfer(idempotencyKey, from, to, amount, description);
        ledger.append(entry);   // same transaction: balance == sum(postings) at every commit

        // and the same transaction again: the world hears of the entry exactly when the book has it
        var requestId = MDC.get(RequestIdFilter.MDC_KEY);
        var posted = new EntryPosted(entry.id(), description,
                List.of(new EntryPosted.Line(debited, amount.negate()), new EntryPosted.Line(credited, amount)));
        outbox.append(EntryPosted.TOPIC, EventEnvelope.of(EntryPosted.TYPE, entry.id(), 1, requestId, requestId, posted));
        return entry;
    }
}
