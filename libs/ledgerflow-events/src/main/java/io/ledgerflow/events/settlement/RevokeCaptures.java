package io.ledgerflow.events.settlement;

import java.util.UUID;

/** Put back every capture taken for the payment. None: nothing happens. No reply. */
public record RevokeCaptures(UUID paymentId) {

    public static final String TYPE = "settlement.RevokeCaptures";
    public static final String TOPIC = IssueCaptures.TOPIC;
}
