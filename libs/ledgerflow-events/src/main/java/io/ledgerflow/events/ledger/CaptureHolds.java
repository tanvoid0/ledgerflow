package io.ledgerflow.events.ledger;

import java.util.UUID;

/** The money behind these holds has moved: mark every open hold with this reference captured. No reply. */
public record CaptureHolds(UUID reference) {

    public static final String TYPE = "ledger.CaptureHolds";
    public static final String TOPIC = ReserveWallets.TOPIC;
}
