package io.ledgerflow.events.issuer;

import java.util.UUID;

/** Undo whatever authorization exists for the payment. None: nothing happens. No reply. */
public record RefundPayment(UUID paymentId) {

    public static final String TYPE = "issuer.RefundPayment";
    public static final String TOPIC = AuthorizePayment.TOPIC;
}
