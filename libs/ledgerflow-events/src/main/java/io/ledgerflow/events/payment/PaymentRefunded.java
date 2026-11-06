package io.ledgerflow.events.payment;

import java.util.UUID;

/**
 * A Captured payment was reversed: the captures were revoked, the money is back with the buyer.
 * Shares the outcome topic with PaymentCaptured and PaymentFailed, so a consumer that already reads
 * one reads all three. accountId and amount let the consumer confirm it is the order it expects.
 */
public record PaymentRefunded(UUID paymentId, UUID accountId, long amountMinor, String currency) {

    public static final String TYPE = "payment.PaymentRefunded";
    public static final String TOPIC = PaymentCaptured.TOPIC;
}
