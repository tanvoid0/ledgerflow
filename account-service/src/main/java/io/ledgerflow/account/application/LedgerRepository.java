package io.ledgerflow.account.application;

import io.ledgerflow.account.domain.model.JournalEntry;
import io.ledgerflow.account.domain.model.Money;

import java.util.Optional;
import java.util.UUID;

public interface LedgerRepository {

    /** Appends the entry and its postings. Throws DuplicateEntryException if the key was already used. */
    void append(JournalEntry entry);

    Optional<JournalEntry> findByIdempotencyKey(String key);

    Money balance(UUID walletId, String currency);
}
