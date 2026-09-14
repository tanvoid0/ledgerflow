package io.ledgerflow.events.settlement;

import java.util.List;
import java.util.UUID;

public record CapturesIssued(UUID paymentId, List<UUID> captureIds) {

    public static final String TYPE = "settlement.CapturesIssued";
    public static final String TOPIC = "ledgerflow.settlement.capture.events.v1";
}
