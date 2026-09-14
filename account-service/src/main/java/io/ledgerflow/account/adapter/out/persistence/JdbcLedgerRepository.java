package io.ledgerflow.account.adapter.out.persistence;

import io.ledgerflow.account.application.DuplicateEntryException;
import io.ledgerflow.account.application.LedgerRepository;
import io.ledgerflow.account.domain.model.JournalEntry;
import io.ledgerflow.account.domain.model.Money;
import io.ledgerflow.account.domain.model.Posting;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * The ledger is append-only: rows are inserted and summed, never loaded and changed.
 * That is why this adapter is plain SQL rather than JPA entities - there is no
 * object state for Hibernate to track.
 */
@Repository
@RequiredArgsConstructor
class JdbcLedgerRepository implements LedgerRepository {

    private final JdbcClient db;

    @Override
    public void append(JournalEntry entry) {
        try {
            db.sql("INSERT INTO journal_entries (id, idempotency_key, description) VALUES (:id, :key, :desc)")
              .param("id", entry.id()).param("key", entry.idempotencyKey()).param("desc", entry.description())
              .update();
        } catch (DuplicateKeyException e) {
            throw new DuplicateEntryException(entry.idempotencyKey());
        }
        for (var p : entry.postings()) {
            db.sql("INSERT INTO postings (entry_id, wallet_id, amount_minor, currency) VALUES (:entry, :wallet, :amount, :ccy)")
              .param("entry", entry.id()).param("wallet", p.walletId())
              .param("amount", p.amount().minorUnits()).param("ccy", p.amount().currency())
              .update();
        }
    }

    @Override
    public Optional<JournalEntry> findByIdempotencyKey(String key) {
        record Head(UUID id, String description) {}

        return db.sql("SELECT id, description FROM journal_entries WHERE idempotency_key = :key")
                 .param("key", key)
                 .query((rs, i) -> new Head(rs.getObject("id", UUID.class), rs.getString("description")))
                 .optional()
                 .map(head -> new JournalEntry(head.id(), key, head.description(),
                         db.sql("SELECT wallet_id, amount_minor, currency FROM postings WHERE entry_id = :id ORDER BY id")
                           .param("id", head.id())
                           .query((rs, i) -> new Posting(rs.getObject("wallet_id", UUID.class),
                                   new Money(rs.getLong("amount_minor"), rs.getString("currency"))))
                           .list()));
    }

    /** The naive balance: add up every posting the wallet has ever had. Correct by construction. */
    @Override
    public Money balance(UUID walletId, String currency) {
        long sum = db.sql("SELECT COALESCE(SUM(amount_minor), 0) FROM postings WHERE wallet_id = :w AND currency = :c")
                     .param("w", walletId).param("c", currency)
                     .query(Long.class).single();
        return new Money(sum, currency);
    }
}
