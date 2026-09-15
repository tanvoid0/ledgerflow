package io.ledgerflow.events.account;

import io.ledgerflow.events.Money;
import io.ledgerflow.events.WalletRef;

import java.util.List;
import java.util.UUID;

/**
 * Money moved in the book of record: one event per journal entry, its lines summing to zero.
 * Keyed by the entry, so a consumer that sums lines per wallet needs no ordering at all.
 */
public record EntryPosted(UUID entryId, String description, List<Line> lines) {

    /** Positive credits the wallet, negative debits it. */
    public record Line(WalletRef wallet, Money amount) {}

    public static final String TYPE = "account.EntryPosted";
    public static final String TOPIC = "ledgerflow.account.entry.events.v1";
}
