package io.ledgerflow.account.domain.model;

import java.util.UUID;

/** One wallet in an account. The label is what a human reads on it, e.g. "A-12". */
public record Wallet(UUID id, String label) {}
