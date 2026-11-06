package io.ledgerflow.account.application;

import io.ledgerflow.account.domain.model.JournalEntry;
import io.ledgerflow.events.Money;
import io.ledgerflow.events.WalletRef;

import java.util.Optional;
import java.util.UUID;

public interface LedgerRepository {

    /** Appends the entry and its postings. Throws DuplicateEntryException if the key was already used. */
    void append(JournalEntry entry);

    Optional<JournalEntry> findByIdempotencyKey(String key);

    Money balance(UUID walletId, String currency);

    /** Debits the wallet only if it can afford it: the wallet debited, or empty when it cannot. One statement, no race. */
    Optional<WalletRef> debitIfSufficient(UUID walletId, Money amount);

    /** Credits the wallet. Never fails on funds. */
    WalletRef credit(UUID walletId, Money amount);

    /** Inserts the account if it is not there already. True when this call created it. */
    boolean createAccountIfAbsent(UUID id, String name);

    /** The account's wallet with this label, creating it if absent. Idempotent on (account, label). */
    UUID walletId(UUID accountId, String label);
}
