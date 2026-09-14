package io.ledgerflow.events.settlement;

import java.util.UUID;

public record IssueFailed(UUID paymentId, String reason) {

    public static final String TYPE = "settlement.IssueFailed";
    public static final String TOPIC = CapturesIssued.TOPIC;
}
