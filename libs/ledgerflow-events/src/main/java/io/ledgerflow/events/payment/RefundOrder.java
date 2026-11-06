package io.ledgerflow.events.payment;

import java.util.UUID;

/**
 * Give the buyer their money back for a payment that already settled. Sent by ticketing when an
 * organiser cancels an event: one per CONFIRMED order. The envelope's correlation id is the order's
 * original request id, so the PaymentRefunded that comes back finds the order again. No reply is
 * awaited inside this command; PaymentRefunded on the outcome topic is the answer. Only a Captured
 * payment moves; anything else is a no-op, so a redelivery is harmless.
 */
public record RefundOrder(UUID paymentId) {

    public static final String TYPE = "payment.RefundOrder";
    public static final String TOPIC = "ledgerflow.payment.refund.commands.v1";
}
