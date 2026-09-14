package io.ledgerflow.events;

import java.util.UUID;

/** A wallet with the hold ledger placed on it. What the saga collects, and what settlement captures. */
public record HeldWallet(UUID holdId, String wallet) {}
