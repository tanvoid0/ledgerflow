package io.ledgerflow.ledger.domain.model;

import io.ledgerflow.events.Money;

import java.time.Instant;
import java.util.UUID;

/** A reservation of funds against one wallet. Money does not move until capture asks account to move it. */
public record FundsHold(UUID id, UUID accountId, String walletCode, Money amount, Status status, Instant expiresAt,
                        UUID reference) {   // what the hold is for, e.g. the payment; null when nobody said

    public enum Status { HELD, RELEASED, CAPTURED }

    public static FundsHold hold(UUID accountId, String walletCode, Money amount, Instant expiresAt, UUID reference) {
        return new FundsHold(UUID.randomUUID(), accountId, walletCode, amount, Status.HELD, expiresAt, reference);
    }
}
