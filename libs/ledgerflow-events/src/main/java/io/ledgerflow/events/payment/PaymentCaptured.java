package io.ledgerflow.events.payment;

import java.util.UUID;

/**
 * The saga reached a terminal Captured state. Ticketing (and anyone else) reads this instead of inferring from ledger commands.
 * accountId and amount let a consumer check the payment was for what it expected, not just that some payment with its correlation id succeeded.
 */
public record PaymentCaptured(UUID paymentId, UUID accountId, long amountMinor, String currency) {

    public static final String TYPE = "payment.PaymentCaptured";
    public static final String TOPIC = "ledgerflow.payment.outcome.events.v1";
}
