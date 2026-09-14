package io.ledgerflow.account.domain.model;

import java.util.UUID;

/** One line of a journal entry. Positive credits the wallet, negative debits it. */
public record Posting(UUID walletId, Money amount) {}
