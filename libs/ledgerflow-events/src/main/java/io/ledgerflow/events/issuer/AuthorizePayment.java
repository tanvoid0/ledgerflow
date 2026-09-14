package io.ledgerflow.events.issuer;

import io.ledgerflow.events.Money;

import java.util.UUID;

/** Ask the issuer to approve the payment. Replies: PaymentAuthorized or PaymentDeclined. */
public record AuthorizePayment(UUID paymentId, Money amount) {

    public static final String TYPE = "issuer.AuthorizePayment";
    public static final String TOPIC = "ledgerflow.issuer.authorization.commands.v1";
}
