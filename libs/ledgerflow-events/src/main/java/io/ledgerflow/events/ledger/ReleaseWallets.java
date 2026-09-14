package io.ledgerflow.events.ledger;

import java.util.UUID;

/** Release every open hold with this reference. Nothing open: nothing happens. No reply. */
public record ReleaseWallets(UUID reference) {

    public static final String TYPE = "ledger.ReleaseWallets";
    public static final String TOPIC = ReserveWallets.TOPIC;
}
