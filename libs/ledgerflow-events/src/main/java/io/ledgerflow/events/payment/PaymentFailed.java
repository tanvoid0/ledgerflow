package io.ledgerflow.events.payment;

import java.util.UUID;

/** The saga reached a terminal Failed state. reason/failedAt mirror payment-service's own FailureReason/Step enums as plain strings, so this library takes no dependency on them. */
public record PaymentFailed(UUID paymentId, String reason, String failedAt, UUID accountId, long amountMinor, String currency) {

    public static final String TYPE = "payment.PaymentFailed";
    public static final String TOPIC = PaymentCaptured.TOPIC;
}
