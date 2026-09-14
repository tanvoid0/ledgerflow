package io.ledgerflow.events.issuer;

import java.util.UUID;

public record PaymentDeclined(UUID paymentId, String reason) {

    public static final String TYPE = "issuer.PaymentDeclined";
    public static final String TOPIC = PaymentAuthorized.TOPIC;
}
