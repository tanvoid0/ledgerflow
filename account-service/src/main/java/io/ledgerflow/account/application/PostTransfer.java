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

    @Transactional
    public JournalEntry transfer(String idempotencyKey, UUID from, UUID to, Money amount, String description) {
        if (amount.minorUnits() <= 0) throw new IllegalArgumentException("amount must be positive");

        // A retry: answer from the book. The UNIQUE index is the guarantee; this lookup saves the write.
        var replay = ledger.findByIdempotencyKey(idempotencyKey);
        if (replay.isPresent()) return replay.get();

        var available = ledger.balance(from, amount.currency());      // 1. read
        if (available.minorUnits() < amount.minorUnits())             // 2. decide
            throw new InsufficientFundsException(from);

        var entry = JournalEntry.transfer(idempotencyKey, from, to, amount, description);
        ledger.append(entry);                                         // 3. write
        return entry;
    }
}
