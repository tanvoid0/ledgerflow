package io.ledgerflow.account.application;

import io.ledgerflow.account.domain.model.JournalEntry;
import io.ledgerflow.account.domain.model.Money;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PostTransfer {

    private final LedgerRepository ledger;

    /**
     * No read-then-decide anywhere. The debit is one conditional UPDATE; the row lock
     * Postgres takes for it is what serialises two racing requests, and the CHECK
     * constraint on the column catches anything that ever gets past it.
     */
    @Transactional
    public JournalEntry transfer(String idempotencyKey, UUID from, UUID to, Money amount, String description) {
        if (amount.minorUnits() <= 0) throw new IllegalArgumentException("amount must be positive");

        // A retry: answer from the book. The UNIQUE index is the guarantee; this lookup saves the write.
        var replay = ledger.findByIdempotencyKey(idempotencyKey);
        if (replay.isPresent()) return replay.get();

        if (!ledger.debitIfSufficient(from, amount))
            throw new InsufficientFundsException(from);
        ledger.credit(to, amount);

        var entry = JournalEntry.transfer(idempotencyKey, from, to, amount, description);
        ledger.append(entry);   // same transaction: balance == sum(postings) at every commit
        return entry;
    }
}
