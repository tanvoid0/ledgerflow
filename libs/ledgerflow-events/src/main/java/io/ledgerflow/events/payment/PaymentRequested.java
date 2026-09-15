package io.ledgerflow.events.payment;

import io.ledgerflow.events.Money;

import java.util.List;
import java.util.UUID;

/** A payment has been requested. Not a saga step: risk-service reads it independently, nothing replies here. */
public record PaymentRequested(UUID paymentId, UUID accountId, List<String> wallets, Money amount, String beneficiary) {

    public static final String TYPE = "payment.PaymentRequested";
    public static final String TOPIC = "ledgerflow.payment.requested.events.v1";
}
