package io.ledgerflow.events;

import java.util.UUID;

/** How every service refers to a wallet. Agreed once, here. */
public record WalletRef(UUID accountId, String label) {}
