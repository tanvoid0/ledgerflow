package io.ledgerflow.events.issuer;

import io.ledgerflow.events.Money;

import java.util.UUID;

public record PaymentAuthorized(UUID paymentId, UUID authorizationId, Money amount) {

    public static final String TYPE = "issuer.PaymentAuthorized";
    public static final String TOPIC = "ledgerflow.issuer.authorization.events.v1";
}
