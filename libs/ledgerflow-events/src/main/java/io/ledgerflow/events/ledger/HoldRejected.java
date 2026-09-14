package io.ledgerflow.events.ledger;

import java.util.UUID;

/**
 * Ledger would not place the hold: a business answer, not a failure. Keyed by the reference.
 * Its own topic: the v1 hold topic's schema pins one payload shape, and the registry refuses to widen it.
 */
public record HoldRejected(UUID reference, String reason) {

    public static final String TYPE = "ledger.HoldRejected";
    public static final String TOPIC = "ledgerflow.ledger.hold-rejected.events.v1";
}
